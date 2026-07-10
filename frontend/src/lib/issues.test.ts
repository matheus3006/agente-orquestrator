import { describe, expect, it } from "vitest";
import type { ActivityEvent, IssueRun, IssueSummary } from "./api";
import {
  corrigeSelo,
  groupIssuesByStatus,
  issueActivityEvents,
  issueBehindLedger,
  issueStatusLed,
  ledgerKeepsPolling,
  ledgerWhen,
  riskNote,
} from "./issues";

const issue = (overrides: Partial<IssueSummary>): IssueSummary => ({
  id: "i1",
  companyId: "c1",
  seq: 1,
  title: "t",
  description: null,
  status: "OPEN",
  riskLevel: 1,
  assigneeAgentId: null,
  executionLockRunId: null,
  createdAt: "2026-07-02T10:00:00Z",
  corrigeIssueId: null,
  corrigeSeq: null,
  ...overrides,
});

describe("corrigeSelo (caso B, ADR-0034 — o selo 'corrige #N' da corretiva)", () => {
  it("corretiva ganha o selo com o #N humano do alvo", () => {
    expect(corrigeSelo(issue({ corrigeIssueId: "orig", corrigeSeq: 12 }))).toBe("corrige #12");
  });

  it("task normal (e alvo colhido, corrigeSeq null) fica sem selo", () => {
    expect(corrigeSelo(issue({}))).toBeNull();
    expect(corrigeSelo(issue({ corrigeIssueId: "orig", corrigeSeq: null }))).toBeNull();
  });
});

describe("groupIssuesByStatus (a ordem do protótipo, grupos vazios somem)", () => {
  it("agrupa na ordem IN_PROGRESS → PENDING_REVIEW → BLOCKED → OPEN → DONE → CANCELLED", () => {
    const groups = groupIssuesByStatus([
      issue({ id: "a", status: "DONE" }),
      issue({ id: "b", status: "IN_PROGRESS" }),
      issue({ id: "c", status: "BLOCKED" }),
      issue({ id: "d", status: "IN_PROGRESS" }),
    ]);
    expect(groups.map((g) => g.status)).toEqual(["IN_PROGRESS", "BLOCKED", "DONE"]);
    expect(groups[0].issues.map((i) => i.id)).toContain("b");
    expect(groups[0].issues.map((i) => i.id)).toContain("d");
  });

  it("labels e LEDs do protótipo", () => {
    const groups = groupIssuesByStatus([
      issue({ status: "IN_PROGRESS" }),
      issue({ id: "x", status: "PENDING_REVIEW" }),
    ]);
    expect(groups[0].label).toBe("Em execução");
    expect(groups[0].led).toBe("running");
    expect(groups[1].label).toBe("Aguardando review");
    expect(groups[1].led).toBe("queued");
    expect(issueStatusLed("OPEN")).toBe("");
  });

  it("dentro do grupo o maior #N vem primeiro (com números visíveis, ordem aleatória leria como bug)", () => {
    // createdAt IDÊNTICO de propósito: prova que a chave de ordenação é o seq, não a data
    // (seq desc ≡ createdAt desc por construção — ADR-0033, alocação monotônica).
    const groups = groupIssuesByStatus([
      issue({ id: "velha", seq: 1, createdAt: "2026-07-01T10:00:00Z" }),
      issue({ id: "nova", seq: 2, createdAt: "2026-07-01T10:00:00Z" }),
    ]);
    expect(groups[0].issues.map((i) => i.id)).toEqual(["nova", "velha"]);
  });

  it("status desconhecido (vocabulário cresce) vira grupo próprio no fim, sem sumir issue", () => {
    const groups = groupIssuesByStatus([
      issue({ status: "OPEN" }),
      issue({ id: "z", status: "STATUS_FUTURO" }),
    ]);
    expect(groups.map((g) => g.status)).toEqual(["OPEN", "STATUS_FUTURO"]);
    expect(groups[1].label).toBe("STATUS_FUTURO");
  });
});

describe("riskNote (nota ao lado do badge no props panel)", () => {
  it.each([
    [5, "review obrigatória"],
    [4, "atenção"],
    [3, "atenção"],
    [2, "baixo"],
    [1, "baixo"],
  ])("R%i → %s", (risk, note) => {
    expect(riskNote(risk)).toBe(note);
  });
});

describe("issueActivityEvents (o feed da issue vem do feed geral, por ID — nunca por texto)", () => {
  const event = (overrides: Partial<ActivityEvent>): ActivityEvent => ({
    id: Math.random().toString(),
    actorType: "BOARD",
    actorId: "b",
    action: "ISSUE_REOPENED",
    entityType: "issue",
    entityId: "issue-1",
    detail: null,
    createdAt: "",
    ...overrides,
  });

  it("pega eventos com entity issue OU detail.issueId (SKILL_ATTACHED)", () => {
    const events = [
      event({ id: "e1", entityId: "issue-1" }),
      event({ id: "e2", entityId: "issue-2" }),
      event({
        id: "e3",
        action: "SKILL_ATTACHED",
        entityType: "issue_skill_attachment",
        entityId: "attach-9",
        detail: { issueId: "issue-1" },
      }),
    ];
    expect(issueActivityEvents(events, "issue-1").map((e) => e.id)).toEqual(["e1", "e3"]);
  });

  it("entityType não-issue com mesmo id NÃO entra (id de review ≠ id de issue)", () => {
    const events = [event({ entityType: "company_skill", entityId: "issue-1" })];
    expect(issueActivityEvents(events, "issue-1")).toEqual([]);
  });
});

// Achados CONFIRMADOS da revisão adversarial da F.4: o detail precisa do
// espelho do mecanismo da F.3 — o ledger (2s) enxerga o settle antes da
// lista de issues (30s), e o poll do ledger não pode morrer com a issue viva.
describe("issueBehindLedger (ledger diz 'liquidado', issue ainda diz IN_PROGRESS)", () => {
  const run = (status: IssueRun["status"]): IssueRun => ({
    id: "r",
    agentId: "a",
    issueId: "i",
    status,
    attempt: 1,
    createdAt: "",
    startedAt: null,
    finishedAt: null,
    lastError: null,
  });
  const issue = (status: string): IssueSummary => ({
    id: "i",
    companyId: "c",
    seq: 1,
    title: "t",
    description: null,
    status,
    riskLevel: 1,
    assigneeAgentId: null,
    executionLockRunId: null,
    createdAt: "",
    corrigeIssueId: null,
    corrigeSeq: null,
  });

  it("IN_PROGRESS com todos os runs terminais → lista está atrás", () => {
    expect(issueBehindLedger(issue("IN_PROGRESS"), [run("FAILED"), run("SUCCEEDED")])).toBe(true);
  });

  it("IN_PROGRESS com run vivo → consistente", () => {
    expect(issueBehindLedger(issue("IN_PROGRESS"), [run("RUNNING"), run("FAILED")])).toBe(false);
  });

  it("ledger vazio ou issue já liquidada → nunca acusa", () => {
    expect(issueBehindLedger(issue("IN_PROGRESS"), [])).toBe(false);
    expect(issueBehindLedger(issue("BLOCKED"), [run("FAILED")])).toBe(false);
  });
});

describe("ledgerKeepsPolling (o detail acompanha issue viva, para na estacionada)", () => {
  const run = (status: IssueRun["status"]): IssueRun => ({
    id: "r",
    agentId: "a",
    issueId: "i",
    status,
    attempt: 1,
    createdAt: "",
    startedAt: null,
    finishedAt: null,
    lastError: null,
  });

  it("run não-terminal no ledger mantém o poll", () => {
    expect(ledgerKeepsPolling("BLOCKED", [run("RUNNING")])).toBe(true);
    expect(ledgerKeepsPolling("DONE", [run("QUEUED")])).toBe(true);
  });

  it("issue viva (OPEN/IN_PROGRESS) mantém o poll mesmo com ledger todo terminal (o run novo precisa aparecer)", () => {
    expect(ledgerKeepsPolling("OPEN", [run("FAILED")])).toBe(true);
    expect(ledgerKeepsPolling("IN_PROGRESS", [])).toBe(true);
  });

  it("issue estacionada com ledger terminal desliga o poll", () => {
    expect(ledgerKeepsPolling("BLOCKED", [run("FAILED")])).toBe(false);
    expect(ledgerKeepsPolling("DONE", [run("SUCCEEDED")])).toBe(false);
    expect(ledgerKeepsPolling("PENDING_REVIEW", [run("SUCCEEDED")])).toBe(false);
    expect(ledgerKeepsPolling("CANCELLED", [])).toBe(false);
  });
});

describe("ledgerWhen (a coluna de tempo do ledger, spec do protótipo)", () => {
  const now = Date.parse("2026-07-02T12:00:00Z");
  const run = (overrides: Partial<IssueRun>): IssueRun => ({
    id: "r1",
    agentId: "a1",
    issueId: "i1",
    status: "FAILED",
    attempt: 1,
    createdAt: "2026-07-02T11:00:00Z",
    startedAt: "2026-07-02T11:00:00Z",
    finishedAt: "2026-07-02T11:00:04Z",
    lastError: "exit 1",
    ...overrides,
  });

  it("run encerrado: 'há X · duração'", () => {
    expect(ledgerWhen(run({}), now)).toBe("há 1h · 00:04");
  });

  it("run RUNNING: elapsed ao vivo + 'em voo'", () => {
    expect(
      ledgerWhen(
        run({ status: "RUNNING", startedAt: "2026-07-02T11:59:49Z", finishedAt: null }),
        now,
      ),
    ).toBe("00:11 em voo");
  });

  it("run na fila (startedAt null): aguardando dispatch", () => {
    expect(ledgerWhen(run({ status: "QUEUED", startedAt: null, finishedAt: null }), now)).toBe(
      "aguardando dispatch",
    );
  });

  it("CANCELLED sem startedAt cai no 'há X' do createdAt", () => {
    expect(
      ledgerWhen(run({ status: "CANCELLED", startedAt: null, finishedAt: null }), now),
    ).toBe("há 1h");
  });
});
