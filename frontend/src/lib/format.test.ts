import { describe, expect, it } from "vitest";
import { fmtAgo, fmtClock, fmtElapsed, shortId } from "./format";

describe("fmtClock", () => {
  it("HH:MM:SS local com zero à esquerda (formato do protótipo)", () => {
    const iso = new Date(2026, 6, 2, 16, 3, 8).toISOString();
    expect(fmtClock(iso)).toBe("16:03:08");
  });

  it("meia-noite não vira 24", () => {
    const iso = new Date(2026, 6, 2, 0, 0, 0).toISOString();
    expect(fmtClock(iso)).toBe("00:00:00");
  });
});

describe("fmtAgo", () => {
  const now = Date.parse("2026-07-02T12:00:00Z");
  const ago = (ms: number) => fmtAgo(new Date(now - ms).toISOString(), now);

  it("segundos", () => {
    expect(ago(5_000)).toBe("há 5s");
  });

  it("nunca negativo (clock skew degrada para 0s)", () => {
    expect(ago(-3_000)).toBe("há 0s");
  });

  it("minutos", () => {
    expect(ago(90_000)).toBe("há 1min");
    expect(ago(59 * 60_000)).toBe("há 59min");
  });

  it("horas — com resto de minutos colado (formato do protótipo)", () => {
    expect(ago(2 * 3_600_000)).toBe("há 2h");
    expect(ago(2 * 3_600_000 + 5 * 60_000)).toBe("há 2h5");
    expect(ago(26 * 3_600_000)).toBe("há 26h");
  });
});

describe("fmtElapsed", () => {
  it("mm:ss com zero à esquerda", () => {
    expect(fmtElapsed(0)).toBe("00:00");
    expect(fmtElapsed(11_000)).toBe("00:11");
    expect(fmtElapsed(71_000)).toBe("01:11");
  });

  it("minutos crescem além de 99 sem quebrar", () => {
    expect(fmtElapsed(100 * 60_000)).toBe("100:00");
  });

  it("negativo degrada para 00:00", () => {
    expect(fmtElapsed(-500)).toBe("00:00");
  });
});

describe("shortId", () => {
  it("primeiros 8 chars de um uuid", () => {
    expect(shortId("213eb466-93a1-4dd0-9256-684ead1235a2")).toBe("213eb466");
  });

  it("string curta passa inteira; nulo degrada para vazio", () => {
    expect(shortId("abc")).toBe("abc");
    expect(shortId(null)).toBe("");
    expect(shortId(undefined)).toBe("");
  });
});
