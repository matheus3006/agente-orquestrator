import { describe, expect, it } from "vitest";
import type { Engine } from "./api";
import { engineLabel, fmtContextWindow, motorMatchesEngine } from "./engines";

// engineLabel resolve o rótulo legível do motor de um agente contra o catálogo
// servido por /engines. Casa o TRIO (adapterType+model+effort, null-aware); um
// motor fora do catálogo (custom) ou o catálogo ainda carregando cai num rótulo
// sintetizado a partir dos eixos — nunca quebra, nunca some.

const engine = (o: Partial<Engine> = {}): Engine => ({
  adapterType: "claude-code",
  model: "opus",
  effort: "ultracode",
  contextWindow: 200000,
  label: "Claude · Opus 4.8 · ultra",
  ...o,
});

const CATALOG: Engine[] = [
  engine(),
  engine({ model: "sonnet", effort: "high", label: "Claude · Sonnet 5 · high" }),
  engine({ adapterType: "codex", model: "gpt-5.5", effort: "xhigh", contextWindow: 256000, label: "GPT · 5.5 · xhigh" }),
  engine({ adapterType: "process", model: null, effort: null, contextWindow: null, label: "Process · demo/echo" }),
];

describe("engineLabel", () => {
  it("resolve o label do catálogo pelo trio exato", () => {
    expect(engineLabel({ adapterType: "codex", model: "gpt-5.5", effort: "xhigh" }, CATALOG)).toBe(
      "GPT · 5.5 · xhigh",
    );
  });

  it("casa null com null (motor sem model/effort = process)", () => {
    expect(engineLabel({ adapterType: "process", model: null, effort: null }, CATALOG)).toBe(
      "Process · demo/echo",
    );
  });

  it("motor fora do catálogo (custom) → sintetiza model · effort", () => {
    expect(engineLabel({ adapterType: "claude-code", model: "opus", effort: "high" }, CATALOG)).toBe(
      "opus · high",
    );
  });

  it("custom sem effort → só o model", () => {
    expect(engineLabel({ adapterType: "claude-code", model: "haiku", effort: null }, CATALOG)).toBe(
      "haiku",
    );
  });

  it("catálogo vazio (ainda carregando) mas com model → sintetiza", () => {
    expect(engineLabel({ adapterType: "codex", model: "gpt-5.5", effort: "high" }, [])).toBe(
      "gpt-5.5 · high",
    );
  });

  it("sem model e sem match → cai no adapterType", () => {
    expect(engineLabel({ adapterType: "process", model: null, effort: null }, [])).toBe("process");
  });
});

describe("motorMatchesEngine", () => {
  it("casa o trio exato (null-aware nos eixos)", () => {
    expect(motorMatchesEngine({ adapterType: "process", model: null, effort: null }, engine({
      adapterType: "process", model: null, effort: null, label: "Process · demo/echo",
    }))).toBe(true);
  });

  it("não casa quando um eixo difere", () => {
    const claudeOpus = engine();
    expect(motorMatchesEngine({ adapterType: "claude-code", model: "opus", effort: "xhigh" }, claudeOpus)).toBe(false);
    expect(motorMatchesEngine({ adapterType: "codex", model: "opus", effort: "ultracode" }, claudeOpus)).toBe(false);
  });
});

describe("fmtContextWindow", () => {
  it("formata em milhares", () => {
    expect(fmtContextWindow(200000)).toBe("200k");
    expect(fmtContextWindow(256000)).toBe("256k");
  });

  it("ausente/zero → null (o process não tem janela)", () => {
    expect(fmtContextWindow(null)).toBeNull();
    expect(fmtContextWindow(0)).toBeNull();
  });
});
