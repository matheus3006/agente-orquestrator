"use client";

import { useEffect, useState } from "react";

/** O relógio de 1s do board: elapsed dos cards e "há Xs" do feed. */
export function useNow(intervalMs: number): number {
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return nowMs;
}
