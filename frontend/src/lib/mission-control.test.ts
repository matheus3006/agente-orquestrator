import { describe, expect, it } from "vitest";
import type { IssueSummary, MissionControlAgent } from "./api";
import {
  cardRunId,
  estadoOcioso,
  issueLineVerb,
  issuesListBehindAggregate,
  issuesListBehindIdleSettle,
  statusClass,
  statusWord,
  stationIssueLine,
  tallyCounts,
} from "./mission-control";

function station(overrides: Partial<MissionControlAgent>): MissionControlAgent {
  return {
    agent: { id: "a1", name: "Atlas", role: "builder" },
    status: "IDLE",
    activeRun: null,
    lastSteps: [],
    lastPreview: null,
    ...overrides,
  };
}

describe("statusWord/statusClass (mapa status→apresentação do protótipo)", () => {
  it.each([
    ["RUNNING", "rodando"],
    ["QUEUED", "na fila"],
    ["BLOCKED", "bloqueado"],
    ["IDLE", "ocioso"],
  ] as const)("%s → %s", (status, word) => {
    expect(statusWord(status)).toBe(word);
    expect(statusClass(status)).toBe(status.toLowerCase());
  });
});

describe("issueLineVerb", () => {
  it.each([
    ["RUNNING", "executando"],
    ["QUEUED", "na fila para"],
    ["BLOCKED", "travado em"],
    ["IDLE", "última issue:"],
  ] as const)("%s → %s", (status, verb) => {
    expect(issueLineVerb(status)).toBe(verb);
  });
});

describe("tallyCounts (derivados do agregado, nunca contados na tela)", () => {
  it("conta por status", () => {
    const counts = tallyCounts([
      station({ status: "RUNNING" }),
      station({ status: "RUNNING" }),
      station({ status: "QUEUED" }),
      station({ status: "BLOCKED" }),
      station({ status: "IDLE" }),
    ]);
    expect(counts).toEqual({ running: 2, queued: 1, blocked: 1, idle: 1 });
  });

  it("roster vazio → tudo zero", () => {
    expect(tallyCounts([])).toEqual({ running: 0, queued: 0, blocked: 0, idle: 0 });
  });
});

describe("stationIssueLine (a linha de issue do card — BLOCKED/IDLE resolvem pela lista)", () => {
  const issue = (overrides: Partial<IssueSummary>): IssueSummary => ({
    id: "i1",
    companyId: "c1",
    seq: 3,
    title: "Deploy do servico de billing",
    description: null,
    status: "BLOCKED",
    riskLevel: 4,
    assigneeAgentId: "a1",
    executionLockRunId: null,
    createdAt: "2026-07-02T10:00:00Z",
    corrigeIssueId: null,
    corrigeSeq: null,
    conversationId: null,
    dirigidaPorGestorAgentId: null,
    ...overrides,
  });

  it("RUNNING usa o #N e o issueTitle do run ativo (verb executando)", () => {
    const line = stationIssueLine(
      station({
        status: "RUNNING",
        activeRun: {
          id: "r",
          issueId: "i",
          issueSeq: 12,
          issueTitle: "Refatorar pagamentos",
          startedAt: "x",
          attempt: 1,
        },
      }),
      [],
    );
    expect(line).toEqual({ note: null, verb: "executando", seq: 12, title: "Refatorar pagamentos" });
  });

  it("RUNNING sem issue (run de rotina) vira nota 'rotina interna'", () => {
    const line = stationIssueLine(
      station({
        status: "RUNNING",
        activeRun: { id: "r", issueId: null, issueSeq: null, issueTitle: null, startedAt: "x", attempt: 1 },
      }),
      [],
    );
    expect(line.note).toBe("rotina interna");
  });

  it("BLOCKED acha a issue BLOCKED designada ao agente (com o #N dela)", () => {
    const line = stationIssueLine(station({ status: "BLOCKED" }), [
      issue({ assigneeAgentId: "outro" }),
      issue({ id: "i2", seq: 5, title: "Deploy do servico de billing" }),
    ]);
    expect(line).toEqual({ note: null, verb: "travado em", seq: 5, title: "Deploy do servico de billing" });
  });

  it("IDLE mostra a issue designada mais recente como 'última issue:'", () => {
    const line = stationIssueLine(station({}), [
      issue({ id: "velha", seq: 1, title: "Antiga", status: "DONE", createdAt: "2026-07-01T10:00:00Z" }),
      issue({ id: "nova", seq: 2, title: "Recente", status: "DONE", createdAt: "2026-07-02T10:00:00Z" }),
    ]);
    expect(line).toEqual({ note: null, verb: "última issue:", seq: 2, title: "Recente" });
  });

  it("IDLE sem issue nenhuma vira a nota de disponível", () => {
    const line = stationIssueLine(station({}), []);
    expect(line.note).toBe("disponível — sem issue designada");
  });
});

// Achado CONFIRMADO da revisão adversarial: o agregado (2s) revela BLOCKED
// antes da lista de issues (staleTime 30s) — o gatilho de invalidação nasce
// deste predicado puro.
describe("issuesListBehindAggregate (agregado diz BLOCKED, lista ainda não)", () => {
  const blockedIssue = (agentId: string): IssueSummary => ({
    id: "i1",
    companyId: "c1",
    seq: 1,
    title: "t",
    description: null,
    status: "BLOCKED",
    riskLevel: 1,
    assigneeAgentId: agentId,
    executionLockRunId: null,
    createdAt: "",
    corrigeIssueId: null,
    corrigeSeq: null,
    conversationId: null,
    dirigidaPorGestorAgentId: null,
  });

  it("agente BLOCKED sem issue BLOCKED correspondente → lista está atrás", () => {
    expect(issuesListBehindAggregate([station({ status: "BLOCKED" })], [])).toBe(true);
    expect(
      issuesListBehindAggregate(
        [station({ status: "BLOCKED" })],
        [{ ...blockedIssue("a1"), status: "IN_PROGRESS" }],
      ),
    ).toBe(true);
  });

  it("agente BLOCKED com a issue BLOCKED na lista → consistente", () => {
    expect(
      issuesListBehindAggregate([station({ status: "BLOCKED" })], [blockedIssue("a1")]),
    ).toBe(false);
  });

  it("sem agente BLOCKED nunca acusa atraso", () => {
    expect(issuesListBehindAggregate([station({ status: "RUNNING" })], [])).toBe(false);
    expect(issuesListBehindAggregate([], [])).toBe(false);
  });
});

describe("cardRunId (qual run o drawer do card abre)", () => {
  it("run ativo vence", () => {
    const agent = station({
      status: "RUNNING",
      activeRun: { id: "run-ativo", issueId: "i", issueSeq: 1, issueTitle: "t", startedAt: "x", attempt: 1 },
      lastSteps: [{ runId: "run-antigo", type: "STDOUT", detail: "a", createdAt: "" }],
    });
    expect(cardRunId(agent)).toBe("run-ativo");
  });

  it("ocioso com rastro abre o run mais recente dos lastSteps", () => {
    const agent = station({
      lastSteps: [
        { runId: "run-antigo", type: "STDOUT", detail: "a", createdAt: "" },
        { runId: "run-recente", type: "RUN_SUCCEEDED", detail: null, createdAt: "" },
      ],
    });
    expect(cardRunId(agent)).toBe("run-recente");
  });

  it("sem run ativo e sem rastro → null (drawer vazio, sem query)", () => {
    expect(cardRunId(station({}))).toBeNull();
  });
});

// #12 (P2-C, Fatia 1): o board parado guia o próximo passo canônico. A detecção
// é PURA e recebe agents/issues (não tally) aceitando undefined → null, para o
// call-site não lavar o `undefined` com `?? []` e classificar "primeiro-uso"
// FALSO numa company com issues ainda em voo (a race de load da page.tsx).
describe("estadoOcioso (o board parado guia o próximo passo — ou null)", () => {
  const idle = () => station({ status: "IDLE" });
  const issue = (status: string): IssueSummary => ({
    id: "i1",
    companyId: "c1",
    seq: 1,
    title: "t",
    description: null,
    status,
    riskLevel: 1,
    assigneeAgentId: "a1",
    executionLockRunId: null,
    createdAt: "2026-07-06T10:00:00Z",
    corrigeIssueId: null,
    corrigeSeq: null,
    conversationId: null,
    dirigidaPorGestorAgentId: null,
  });

  it("dados incompletos (agents/issues undefined) → null (guard da race de load)", () => {
    expect(estadoOcioso(undefined, "Ada", [])).toBeNull();
    expect(estadoOcioso([idle()], "Ada", undefined)).toBeNull();
  });

  it("sem CEO → null (o composer já avisa que falta contratar)", () => {
    expect(estadoOcioso([idle()], null, [])).toBeNull();
  });

  it.each(["RUNNING", "QUEUED", "BLOCKED"] as const)(
    "algum agente %s → null (não é ocioso; BLOCKED já aponta outro passo)",
    (status) => {
      expect(estadoOcioso([idle(), station({ status })], "Ada", [])).toBeNull();
    },
  );

  it("todo idle + CEO + zero issues → primeiro-uso", () => {
    expect(estadoOcioso([idle()], "Ada", [])).toBe("primeiro-uso");
  });

  it("todo idle + CEO + todas as issues terminais (DONE/CANCELLED) → entregue", () => {
    expect(estadoOcioso([idle()], "Ada", [issue("DONE"), issue("CANCELLED")])).toBe("entregue");
  });

  it("todo idle + CEO + alguma issue não-terminal → quieto", () => {
    expect(estadoOcioso([idle()], "Ada", [issue("DONE"), issue("OPEN")])).toBe("quieto");
  });

  it("todo idle + CEO + review pendente → quieto (a copy aponta a pendência, D-12-04-b)", () => {
    expect(estadoOcioso([idle()], "Ada", [issue("PENDING_REVIEW")])).toBe("quieto");
  });
});

// Espelho do padrão F.3 (issuesListBehindAggregate/issueBehindLedger): o agregado
// (poll 2s) já vê o board PARAR, mas a lista de issues (staleTime 30s) ainda tem
// a issue IN_PROGRESS — sem invalidar, "entregue" nunca chega e "quieto" mente.
describe("issuesListBehindIdleSettle (board parou, lista de issues ainda IN_PROGRESS)", () => {
  const issue = (status: string): IssueSummary => ({
    id: "i1",
    companyId: "c1",
    seq: 1,
    title: "t",
    description: null,
    status,
    riskLevel: 1,
    assigneeAgentId: "a1",
    executionLockRunId: null,
    createdAt: "",
    corrigeIssueId: null,
    corrigeSeq: null,
    conversationId: null,
    dirigidaPorGestorAgentId: null,
  });

  it("todo idle + issue ainda IN_PROGRESS → lista atrasada (invalidar)", () => {
    expect(issuesListBehindIdleSettle([station({ status: "IDLE" })], [issue("IN_PROGRESS")])).toBe(
      true,
    );
  });

  it("todo idle mas nenhuma IN_PROGRESS → não invalida (estados estacionários)", () => {
    expect(issuesListBehindIdleSettle([station({ status: "IDLE" })], [issue("PENDING_REVIEW")])).toBe(
      false,
    );
    expect(issuesListBehindIdleSettle([station({ status: "IDLE" })], [issue("OPEN")])).toBe(false);
    expect(issuesListBehindIdleSettle([station({ status: "IDLE" })], [])).toBe(false);
  });

  it("algum agente ativo → não invalida (ainda rodando; o poll de 2s cobre)", () => {
    expect(
      issuesListBehindIdleSettle([station({ status: "RUNNING" })], [issue("IN_PROGRESS")]),
    ).toBe(false);
  });
});
