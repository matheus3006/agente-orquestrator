// Helpers puros do preview vivo (F.10/ADR-0023). O runtime (vivo/porta/TTL)
// mora no backend; aqui só apresentação: TTL legível, o countdown local entre
// refetches, o aviso "workspace avançou" e a falha de subida em motivo + tail.

/** O separador que o backend usa entre o motivo e as últimas linhas do log. */
const TAIL_SEPARATOR = "\n--- ultimas linhas ---\n";

/** TTL como mm:ss — nunca negativo (o reaper pode estar a caminho). */
export function fmtTtl(seconds: number): string {
  const clamped = Math.max(0, seconds);
  const minutes = Math.floor(clamped / 60);
  const rest = clamped % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}

/** Desconta do TTL reportado o tempo decorrido desde o fetch (countdown local). */
export function ttlRemainingNow(ttlAtFetchSeconds: number, elapsedSinceFetchMs: number): number {
  return Math.max(0, ttlAtFetchSeconds - Math.floor(elapsedSinceFetchMs / 1000));
}

/**
 * O caso raro do ADR-0023 §6: o manifest vigente é de run POSTERIOR ao run sob
 * revisão — rodar mostraria um alvo que avançou além do que se está aprovando.
 */
export function workspaceAvancou(manifestRunId: string, reviewRunId: string): boolean {
  return manifestRunId !== reviewRunId;
}

/** A falha legível de subida, separada para a UI: motivo curto + tail em bloco. */
export function splitStartFailure(error: string): { reason: string; tail: string | null } {
  const separatorAt = error.indexOf(TAIL_SEPARATOR);
  if (separatorAt < 0) {
    return { reason: error, tail: null };
  }
  return {
    reason: error.slice(0, separatorAt),
    tail: error.slice(separatorAt + TAIL_SEPARATOR.length),
  };
}
