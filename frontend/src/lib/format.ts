// Formatadores mono do board — portados 1:1 do protótipo aprovado (P.1).
// Puros e determinísticos (recebem o "agora" quando dependem dele).

const pad2 = (n: number) => String(n).padStart(2, "0");

/** HH:MM:SS local — o timestamp de cada linha do terminal. */
export function fmtClock(createdAt: string): string {
  const date = new Date(createdAt);
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

/** "há 5s" / "há 3min" / "há 2h5" — o relógio do feed e da fila de review. */
export function fmtAgo(createdAt: string, nowMs: number): string {
  const seconds = Math.max(0, Math.floor((nowMs - Date.parse(createdAt)) / 1000));
  if (seconds < 60) {
    return `há ${seconds}s`;
  }
  if (seconds < 3600) {
    return `há ${Math.floor(seconds / 60)}min`;
  }
  const hours = Math.floor(seconds / 3600);
  const restMinutes = Math.floor((seconds % 3600) / 60);
  return `há ${hours}h${restMinutes || ""}`;
}

/** "mm:ss" com tabular-nums — o elapsed do card RUNNING (tick de 1s). */
export function fmtElapsed(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  return `${pad2(Math.floor(seconds / 60))}:${pad2(seconds % 60)}`;
}

/** Primeiros 8 chars de um UUID — como IDs aparecem em TODO o board. */
export function shortId(id: string | null | undefined): string {
  return (id ?? "").slice(0, 8);
}
