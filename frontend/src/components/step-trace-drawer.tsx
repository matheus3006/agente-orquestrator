"use client";

import { X } from "lucide-react";
import { useEffect, useRef } from "react";
import { AgentDot } from "@/components/agent-dot";
import { fmtClock, shortId } from "@/lib/format";
import { isTerminalRunStatus, lineKind } from "@/lib/transcript";
import { useRunTranscript } from "@/lib/use-run-transcript";
import { cn } from "@/lib/utils";

// O drawer de StepTrace — COMPARTILHADO entre Mission Control (F.3) e o
// ledger de Issues (F.4). Streaming incremental por nextOffset enquanto o
// run está vivo; runStatus vem junto da página e desliga o poll no terminal.
// Fica sempre montado (a saída de 260ms precisa do conteúdo durante a
// animação): `open` controla classe + query.

export interface TranscriptRequest {
  runId: string | null;
  issueTitle: string | null;
  agentName: string | null;
}

export function StepTraceDrawer({
  companyId,
  request,
  open,
  onClose,
}: {
  companyId: string | undefined;
  request: TranscriptRequest | null;
  open: boolean;
  onClose: () => void;
}) {
  const transcriptQuery = useRunTranscript(companyId, request?.runId ?? null, open);
  const transcript = transcriptQuery.data;
  const steps = transcript?.steps ?? [];

  const scrollRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el && open) {
      el.scrollTop = el.scrollHeight;
    }
  }, [steps.length, open]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      // defaultPrevented = um dialog (radix) consumiu este ESC — não fechar
      // o drawer junto (achado da revisão: drawer + dialog abertos).
      if (event.key === "Escape" && !event.defaultPrevented) {
        onClose();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  const terminal = isTerminalRunStatus(transcript?.runStatus ?? null);
  // Modo CONGELADO (spec: openDrawerForFrozenRun) = sem agente vivo por trás:
  // o run id fica só no título, o sub é só a issue, o foot diz "transcript
  // completo". Modo agente (renderDrawerBody) mantém "issue · run xxx".
  const frozen = !request?.agentName;

  return (
    <>
      <div className={cn("drawer-scrim", open && "open")} onClick={onClose} />
      {/* inert (não aria-hidden): fechado, nada aqui é focável nem legível por
          AT — e o browser não briga quando o foco estava no botão Fechar. */}
      <aside className={cn("drawer", open && "open")} aria-label="Transcript do run" inert={!open}>
        {request && (
          <>
            <div className="drawer-head">
              {request.agentName && <AgentDot name={request.agentName} />}
              <div>
                <div className="drawer-title">
                  {request.agentName
                    ? `${request.agentName} — StepTrace`
                    : `StepTrace — run ${shortId(request.runId)}`}
                </div>
                <div className="drawer-sub">
                  {frozen
                    ? (request.issueTitle ?? "")
                    : `${request.issueTitle ? `${request.issueTitle} · ` : ""}run ${
                        request.runId ? shortId(request.runId) : "—"
                      }`}
                </div>
              </div>
              {transcript?.runStatus && (
                <span className={`run-status ${transcript.runStatus}`}>{transcript.runStatus}</span>
              )}
              <button className="btn ghost drawer-close" onClick={onClose} aria-label="Fechar">
                <X size={13} strokeWidth={2} />
              </button>
            </div>

            <div className="transcript" ref={scrollRef}>
              {steps.length === 0 ? (
                <div className="term-empty">sem steps ainda</div>
              ) : (
                // append-only ⇒ índice é chave ESTÁVEL (nunca reordena/remove)
                steps.map((step, index) => {
                  const kind = lineKind(step.type, step.detail);
                  return (
                    <div key={index} className={cn("term-line", kind !== "plain" && kind)}>
                      <span className="t">{fmtClock(step.createdAt)}</span>
                      <span className="type-tag">{step.type}</span>
                      <span>{step.detail ?? ""}</span>
                    </div>
                  );
                })
              )}
            </div>

            <div className="drawer-foot">
              <span>
                {frozen && terminal
                  ? `${steps.length} steps · transcript completo`
                  : `offset incremental · ${steps.length} steps`}
              </span>
              <span style={{ flex: 1 }} />
              <span>
                {!request.runId
                  ? "sem run — nada a pollar"
                  : terminal
                    ? frozen
                      ? "run encerrado"
                      : "run encerrado — poll parado"
                    : "poll 2s ativo"}
              </span>
            </div>
          </>
        )}
      </aside>
    </>
  );
}
