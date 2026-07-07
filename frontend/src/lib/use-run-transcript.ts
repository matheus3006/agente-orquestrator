"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRunSteps } from "./api";
import { queryKeys } from "./query-keys";
import { emptyTranscript, isTerminalRunStatus, mergeStepsPage, type Transcript } from "./transcript";

// O streaming do drawer (ADR-0013): o transcript ACUMULADO é o dado da query.
// Cada tick busca `steps?offset=nextOffset` a partir do acumulado no cache e
// merga (append-only ⇒ estável). O runStatus vem junto da página: terminal →
// refetchInterval desliga sozinho (o front para de pollar sem 2º endpoint).

const PAGE_LIMIT = 500; // clamp máximo do endpoint — menos round-trips no catch-up
const MAX_CATCHUP_PAGES = 10; // transcript gigante não trava um tick para sempre

export function useRunTranscript(
  companyId: string | undefined,
  runId: string | null,
  enabled: boolean,
) {
  const queryClient = useQueryClient();
  return useQuery<Transcript>({
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
    refetchInterval: (query) => {
      const transcript = query.state.data;
      return transcript && isTerminalRunStatus(transcript.runStatus) && transcript.caughtUp
        ? false
        : 2000;
    },
    refetchIntervalInBackground: false,
  });
}
