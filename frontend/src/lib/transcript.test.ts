import { describe, expect, it } from "vitest";
import type { RunStepsPage } from "./api";
import {
  emptyTranscript,
  isTerminalRunStatus,
  lineKind,
  mergeStepsPage,
  stepText,
} from "./transcript";

const RUN_ID = "72d0cd8a-de8d-43ac-8981-8c622e8b7072";

function page(overrides: Partial<RunStepsPage>): RunStepsPage {
  return { runId: RUN_ID, runStatus: "RUNNING", steps: [], nextOffset: 0, ...overrides };
}

const step = (detail: string | null, type = "STDOUT") => ({
  type,
  detail,
  createdAt: "2026-07-02T17:44:23.156258Z",
});

const LIMIT = 500;

describe("mergeStepsPage (merge incremental por offset — o coração do streaming)", () => {
  it("página inicial vira o transcript", () => {
    const merged = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("[plan] lendo issue"), step(null, "RUN_STARTED")], nextOffset: 2 }),
      LIMIT,
    );
    expect(merged.steps).toHaveLength(2);
    expect(merged.nextOffset).toBe(2);
    expect(merged.runStatus).toBe("RUNNING");
  });

  it("delta é APPENDADO (append-only ⇒ offset estável)", () => {
    const inicial = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a"), step("b")], nextOffset: 2 }),
      LIMIT,
    );
    const merged = mergeStepsPage(inicial, page({ steps: [step("c")], nextOffset: 3 }), LIMIT);
    expect(merged.steps.map((s) => s.detail)).toEqual(["a", "b", "c"]);
    expect(merged.nextOffset).toBe(3);
  });

  it("página vazia sem mudança de status devolve a MESMA referência (poll sem novidade não re-renderiza)", () => {
    const inicial = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a")], nextOffset: 1 }),
      LIMIT,
    );
    const merged = mergeStepsPage(inicial, page({ steps: [], nextOffset: 1 }), LIMIT);
    expect(merged).toBe(inicial);
  });

  it("página vazia com runStatus novo atualiza SÓ o status (fim do run chega sem steps novos)", () => {
    const inicial = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a")], nextOffset: 1 }),
      LIMIT,
    );
    const merged = mergeStepsPage(
      inicial,
      page({ steps: [], nextOffset: 1, runStatus: "SUCCEEDED" }),
      LIMIT,
    );
    expect(merged.runStatus).toBe("SUCCEEDED");
    expect(merged.steps).toHaveLength(1);
    expect(merged.nextOffset).toBe(1);
  });

  it("página de OUTRO run substitui o transcript inteiro (nunca mistura)", () => {
    const inicial = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a")], nextOffset: 1 }),
      LIMIT,
    );
    const merged = mergeStepsPage(
      inicial,
      page({ runId: "outro-run", steps: [step("x")], nextOffset: 1 }),
      LIMIT,
    );
    expect(merged.runId).toBe("outro-run");
    expect(merged.steps.map((s) => s.detail)).toEqual(["x"]);
  });

  // Achado CONFIRMADO da revisão adversarial: run terminal com página CHEIA
  // não pode desligar o poll — o rabo do transcript ainda não foi drenado.
  it("página CHEIA marca caughtUp=false mesmo com status terminal (poll continua até drenar)", () => {
    const cheia = Array.from({ length: 3 }, (_, i) => step(`s${i}`));
    const merged = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: cheia, nextOffset: 3, runStatus: "SUCCEEDED" }),
      3,
    );
    expect(merged.caughtUp).toBe(false);
  });

  it("página curta ou vazia marca caughtUp=true (drenado)", () => {
    const curta = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a")], nextOffset: 1 }),
      LIMIT,
    );
    expect(curta.caughtUp).toBe(true);
    const vazia = mergeStepsPage(curta, page({ steps: [], nextOffset: 1 }), LIMIT);
    expect(vazia.caughtUp).toBe(true);
  });

  it("página vazia após uma cheia vira caughtUp=true preservando os steps", () => {
    const cheia = mergeStepsPage(
      emptyTranscript(RUN_ID),
      page({ steps: [step("a"), step("b")], nextOffset: 2 }),
      2,
    );
    expect(cheia.caughtUp).toBe(false);
    const drenada = mergeStepsPage(cheia, page({ steps: [], nextOffset: 2 }), 2);
    expect(drenada.caughtUp).toBe(true);
    expect(drenada.steps).toHaveLength(2);
  });
});

describe("isTerminalRunStatus (quando o poll do transcript PARA)", () => {
  it.each([
    ["SUCCEEDED", true],
    ["FAILED", true],
    ["CANCELLED", true],
    ["INTERRUPTED", true], // F-N4 (ADR-0040): parada pelo operador tambem encerra o poll
    ["RUNNING", false],
    ["QUEUED", false],
    ["RECOVERING", false],
  ] as const)("%s → %s", (status, expected) => {
    expect(isTerminalRunStatus(status)).toBe(expected);
  });

  it("status ainda desconhecido (transcript não carregado) não é terminal", () => {
    expect(isTerminalRunStatus(null)).toBe(false);
  });
});

describe("lineKind (cores do terminal por tipo/prefixo — spec do protótipo)", () => {
  it("[error] e RUN_FAILED → error", () => {
    expect(lineKind("STDOUT", "[error] registry recusou")).toBe("error");
    expect(lineKind("RUN_FAILED", "processo terminou com exit 1")).toBe("error");
    expect(lineKind("RUN_FAILED", null)).toBe("error");
  });

  it("[warn] → warn", () => {
    expect(lineKind("STDOUT", "[warn] teste flaky detectado")).toBe("warn");
  });

  it("[done] e RUN_SUCCEEDED → ok", () => {
    expect(lineKind("STDOUT", "[done] issue concluida")).toBe("ok");
    expect(lineKind("RUN_SUCCEEDED", null)).toBe("ok");
  });

  it("evento de ciclo de vida não-STDOUT → meta", () => {
    expect(lineKind("RUN_STARTED", null)).toBe("meta");
    expect(lineKind("RUN_QUEUED", "wakeup: board")).toBe("meta");
  });

  it("STDOUT comum → plain", () => {
    expect(lineKind("STDOUT", "[step 1/5] clonando workspace")).toBe("plain");
    expect(lineKind("STDOUT", null)).toBe("plain");
  });
});

describe("stepText (detail null NUNCA vira 'null' — gotcha do handoff)", () => {
  it("detail presente passa direto", () => {
    expect(stepText({ type: "STDOUT", detail: "[plan] x", createdAt: "" })).toBe("[plan] x");
  });

  it("detail null estiliza pelo type", () => {
    expect(stepText({ type: "RUN_STARTED", detail: null, createdAt: "" })).toBe("— RUN_STARTED —");
  });
});
