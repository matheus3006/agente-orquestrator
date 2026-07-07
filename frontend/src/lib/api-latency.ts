// A latência REAL do último request do board (browser) — alimenta a sys-line
// da sidebar ("poll 2.0s · 38ms" do protótipo, agora com número de verdade).
// Store minimalista para useSyncExternalStore; gravada pelo apiFetch.

let lastLatencyMs: number | null = null;
const listeners = new Set<() => void>();

export function recordApiLatency(ms: number): void {
  lastLatencyMs = ms;
  for (const listener of listeners) {
    listener();
  }
}

export function readApiLatency(): number | null {
  return lastLatencyMs;
}

export function subscribeApiLatency(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
