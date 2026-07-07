import type { RunStatus, RunStepsPage, TranscriptStep } from "./api";

// O streaming do StepTrace (ADR-0013): o transcript acumulado É o dado da
// query — cada poll de 2s busca `steps?offset=nextOffset` e passa por aqui.
// Append-only no servidor ⇒ offset de contagem estável ⇒ merge = concatenar.

export interface Transcript {
  runId: string;
  /** null até a primeira página chegar. */
  runStatus: RunStatus | null;
  steps: TranscriptStep[];
  nextOffset: number;
  /**
   * true quando a última página veio curta/vazia = o servidor foi DRENADO.
   * O poll só pode parar em (terminal && caughtUp): status terminal com
   * página cheia ainda tem rabo de transcript por buscar (achado da revisão).
   */
  caughtUp: boolean;
}

export function emptyTranscript(runId: string): Transcript {
  return { runId, runStatus: null, steps: [], nextOffset: 0, caughtUp: false };
}

/**
 * Merge incremental por offset. Página vazia sem mudança de status devolve a
 * MESMA referência (poll sem novidade não re-renderiza nada); página de outro
 * run substitui o transcript inteiro (nunca mistura runs).
 */
export function mergeStepsPage(prev: Transcript, page: RunStepsPage, pageLimit: number): Transcript {
  const caughtUp = page.steps.length < pageLimit;
  if (page.runId !== prev.runId) {
    return {
      runId: page.runId,
      runStatus: page.runStatus,
      steps: [...page.steps],
      nextOffset: page.nextOffset,
      caughtUp,
    };
  }
  if (page.steps.length === 0) {
    return prev.runStatus === page.runStatus && prev.caughtUp
      ? prev
      : { ...prev, runStatus: page.runStatus, caughtUp: true };
  }
  return {
    runId: prev.runId,
    runStatus: page.runStatus,
    steps: [...prev.steps, ...page.steps],
    nextOffset: page.nextOffset,
    caughtUp,
  };
}

/** Terminal = o front PARA de pollar (o runStatus vem junto da página, F.0). */
export function isTerminalRunStatus(status: RunStatus | null): boolean {
  return status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED";
}

export type LineKind = "error" | "warn" | "ok" | "meta" | "plain";

/** Cor da linha do terminal por tipo/prefixo — a spec do protótipo. */
export function lineKind(type: string, detail: string | null): LineKind {
  if (type === "RUN_FAILED" || detail?.startsWith("[error]")) {
    return "error";
  }
  if (detail?.startsWith("[warn]")) {
    return "warn";
  }
  if (detail?.startsWith("[done]") || type === "RUN_SUCCEEDED") {
    return "ok";
  }
  if (type !== "STDOUT") {
    return "meta";
  }
  return "plain";
}

/** detail null (eventos de ciclo de vida) NUNCA vira "null" — vira o type. */
export function stepText(step: TranscriptStep): string {
  return step.detail ?? `— ${step.type} —`;
}
