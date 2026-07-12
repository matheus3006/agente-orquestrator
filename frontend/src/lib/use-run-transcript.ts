"use client";

import { useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRunSteps, streamRunSteps, type RunStatus, type TranscriptStep } from "./api";
import { queryKeys } from "./query-keys";
import { emptyTranscript, isTerminalRunStatus, mergeStepsPage, type Transcript } from "./transcript";

// O streaming do drawer (ADR-0013): o transcript ACUMULADO é o dado da query.
// Cada tick busca `steps?offset=nextOffset` a partir do acumulado no cache e
// merga (append-only ⇒ estável). O runStatus vem junto da página: terminal →
// refetchInterval desliga sozinho (o front para de pollar sem 2º endpoint).
//
// F-N4 (ADR-0040): a partir do catch-up inicial, o SSE assume o "ao vivo" —
// o poll de 2s vira FALLBACK (só roda se o SSE ainda não conectou ou caiu).
// A lógica de catch-up acima fica INTOCADA: o SSE só entra depois de caughtUp.

const PAGE_LIMIT = 500; // clamp máximo do endpoint — menos round-trips no catch-up
const MAX_CATCHUP_PAGES = 10; // transcript gigante não trava um tick para sempre

export function useRunTranscript(
  companyId: string | undefined,
  runId: string | null,
  enabled: boolean,
) {
  const queryClient = useQueryClient();
  // Ref, não state: só é lido pelo agendador INTERNO do react-query
  // (refetchInterval), nunca pelo render — virar state disparava um
  // "setState síncrono no efeito" (cascading render, achado do lint).
  const sseLiveRef = useRef(false);

  const query = useQuery<Transcript>({
    queryKey: queryKeys.runSteps(companyId ?? "", runId ?? ""),
    enabled: enabled && !!companyId && !!runId,
    queryFn: async () => {
      const key = queryKeys.runSteps(companyId as string, runId as string);
      let transcript =
        queryClient.getQueryData<Transcript>(key) ?? emptyTranscript(runId as string);
      for (let pagesFetched = 0; pagesFetched < MAX_CATCHUP_PAGES; pagesFetched++) {
        const page = await getRunSteps(
          companyId as string,
          runId as string,
          transcript.nextOffset,
          PAGE_LIMIT,
        );
        transcript = mergeStepsPage(transcript, page, PAGE_LIMIT);
        if (transcript.caughtUp) {
          break;
        }
      }
      return transcript;
    },
    // Parar SÓ com terminal && caughtUp: run terminal que estourou o bound do
    // catch-up ainda tem rabo por drenar — o próximo tick continua de onde parou.
    // Com SSE saudável (sseLive), o poll desliga ANTES do terminal — o SSE cobre o ao vivo.
    refetchInterval: (query) => {
      const transcript = query.state.data;
      if (transcript && isTerminalRunStatus(transcript.runStatus) && transcript.caughtUp) {
        return false;
      }
      return sseLiveRef.current ? false : 2000;
    },
    refetchIntervalInBackground: false,
  });

  const transcript = query.data;
  const caughtUp = transcript?.caughtUp ?? false;
  const terminal = isTerminalRunStatus(transcript?.runStatus ?? null);

  useEffect(() => {
    // Só conecta DEPOIS do catch-up inicial (offset conhecido) e enquanto o run
    // ainda está em voo — run já terminal na abertura do drawer não tem "ao vivo".
    if (!enabled || !companyId || !runId || !caughtUp || terminal) {
      return;
    }
    // queryKeys.runSteps() devolve um array NOVO a cada chamada — calculado AQUI
    // dentro (nunca como dependência do efeito acima, ou o efeito reconectaria o
    // SSE a cada render: achado do VENDO, a conexão abria/abortava em looping).
    const key = queryKeys.runSteps(companyId, runId);
    const startOffset = queryClient.getQueryData<Transcript>(key)?.nextOffset ?? 0;
    const stream = streamRunSteps(companyId, runId, startOffset, {
      onStep: (step: TranscriptStep) => {
        queryClient.setQueryData<Transcript>(key, (prev) =>
          prev
            ? { ...prev, steps: [...prev.steps, step], nextOffset: prev.nextOffset + 1 }
            : prev,
        );
      },
      onStatus: (status: RunStatus) => {
        queryClient.setQueryData<Transcript>(key, (prev) =>
          prev ? { ...prev, runStatus: status, caughtUp: true } : prev,
        );
      },
      onError: () => {
        // Cai pro poll REST (fallback) — sem retry automático: o poll já cobre
        // a continuidade, e o próximo motivo de re-render reabre o SSE.
        sseLiveRef.current = false;
      },
    });
    sseLiveRef.current = true;

    return () => {
      sseLiveRef.current = false;
      stream.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, companyId, runId, caughtUp, terminal]);

  return query;
}
