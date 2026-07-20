import { describe, expect, it } from "vitest";
import type { AgentSummary, IssueSummary, ReviewSummary } from "./api";
import { resolveReviewCard } from "./reviews";

const review = (o: Partial<ReviewSummary> = {}): ReviewSummary => ({
  id: "r-uuid-longa-1234",
  issueId: "iss-1",
  runId: "run-1",
  status: "PENDING",
  reviewerActorType: null,
  reviewerActorId: null,
  decisionReason: null,
  requestedAt: "2026-07-02T10:00:00Z",
  decidedAt: null,
  area: "operacao",
  racionalTipo: "LOGICO",
  racionalProbe: "Alguma ressalva ou risco que valha registrar?",
  parecer: null,
  parecerPorAgentId: null,
  parecerEm: null,
  ...o,
});

const issue = (o: Partial<IssueSummary> = {}): IssueSummary => ({
  id: "iss-1",
  companyId: "c1",
  seq: 9,
  title: "Publicar release notes",
  description: null,
  status: "PENDING_REVIEW",
  riskLevel: 5,
  assigneeAgentId: "ag-1",
  executionLockRunId: null,
  createdAt: "2026-07-02T09:00:00Z",
  corrigeIssueId: null,
  corrigeSeq: null,
  conversationId: null,
  dirigidaPorGestorAgentId: null,
  ...o,
});

const agent = (o: Partial<AgentSummary> = {}): AgentSummary => ({
  id: "ag-1",
  companyId: "c1",
  name: "Atlas",
  role: "architect",
  persona: null,
  reportsToId: null,
  adapterType: "process",
  executionTarget: "LOCAL",
  model: null,
  effort: null,
  pinnedTemplateVersionId: null,
  pinnedTemplateId: null,
  teamId: null,
  createdAt: "2026-07-01T00:00:00Z",
  ...o,
});

describe("resolveReviewCard (a ReviewResponse enxuta resolvida pela lista de issues/agentes)", () => {
  it("resolve #N, título, risco e assignee pela issue + agente correspondentes", () => {
    const card = resolveReviewCard(review(), [issue()], [agent()]);
    expect(card.seq).toBe(9); // o card mostra "#9 Publicar release notes" (ADR-0033)
    expect(card.title).toBe("Publicar release notes");
    expect(card.riskLevel).toBe(5);
    expect(card.assigneeName).toBe("Atlas");
    expect(card.reviewId).toBe("r-uuid-longa-1234"); // âncora inteira, nunca encurtada
  });

  it("issue ainda não resolvida (lista carregando): fallback de título + risco null (sem badge)", () => {
    const card = resolveReviewCard(review(), undefined, [agent()]);
    expect(card.riskLevel).toBeNull();
    expect(card.seq).toBeNull(); // sem a issue não há #N — o fallback segue o id curto
    expect(card.title).toContain("iss-1");
    expect(card.assigneeName).toBeUndefined(); // sem a issue, não afirma "livre"
  });

  it("issue sem assignee → null (— livre)", () => {
    const card = resolveReviewCard(review(), [issue({ assigneeAgentId: null })], [agent()]);
    expect(card.assigneeName).toBeNull();
  });

  it("agentes ainda carregando (issue designada) → undefined (mostra —, não afirma livre)", () => {
    const card = resolveReviewCard(review(), [issue()], undefined);
    expect(card.assigneeName).toBeUndefined();
  });

  it("agente designado mas fora da lista → null (mesma semântica da página de issues)", () => {
    const card = resolveReviewCard(review(), [issue({ assigneeAgentId: "sumiu" })], [agent()]);
    expect(card.assigneeName).toBeNull();
  });

  // F-C1 (C14): a pergunta não-bloqueante do approve vem nas PALAVRAS da política
  // (racionalProbe) + área/tipo do artefato (C11/D9) — a UI é dona só do MOMENTO.
  it("carrega área, tipo e o probe da política no card (F-C1/C14)", () => {
    const card = resolveReviewCard(
      review({
        area: "design",
        racionalTipo: "INTUICAO",
        racionalProbe: "Probe maieutico (ADR-0020/D10): o que exatamente te agrada aqui?",
      }),
      [issue()],
      [agent()],
    );
    expect(card.area).toBe("design");
    expect(card.racionalTipo).toBe("INTUICAO");
    expect(card.racionalProbe).toContain("maieutico"); // as palavras vêm da política, nunca da UI
  });

  it("degrada sem quebrar quando o backend não resolve área/probe (issue sumida)", () => {
    const card = resolveReviewCard(
      review({ area: null, racionalTipo: null, racionalProbe: null }),
      [issue()],
      [agent()],
    );
    expect(card.area).toBeNull();
    expect(card.racionalTipo).toBeNull();
    expect(card.racionalProbe).toBeNull();
  });

  it("carrega os campos do ciclo (runId, decisionReason, decidedAt, status) sem transformar", () => {
    const card = resolveReviewCard(
      review({
        status: "REJECTED",
        decisionReason: "faltou teste",
        decidedAt: "2026-07-02T11:00:00Z",
      }),
      [issue({ status: "BLOCKED" })],
      [agent()],
    );
    expect(card.runId).toBe("run-1");
    expect(card.decisionReason).toBe("faltou teste");
    expect(card.decidedAt).toBe("2026-07-02T11:00:00Z");
    expect(card.status).toBe("REJECTED");
  });

  // ---------- F-ORG-C (ADR-0048): parecer do gestor + selo da decisão delegada ----------

  it("resolve o nome de quem opinou pelo mapa de agentes", () => {
    const card = resolveReviewCard(
      review({
        parecer: "APROVARIA — o teste do 409 está lá",
        parecerPorAgentId: "ag-2",
        parecerEm: "2026-07-02T10:30:00Z",
      }),
      [issue()],
      [agent(), agent({ id: "ag-2", name: "Ana" })],
    );
    expect(card.parecer).toBe("APROVARIA — o teste do 409 está lá");
    expect(card.parecerPorName).toBe("Ana");
    expect(card.parecerEm).toBe("2026-07-02T10:30:00Z");
  });

  it("sem parecer, o card não inventa autor nem texto", () => {
    const card = resolveReviewCard(review(), [issue()], [agent()]);
    expect(card.parecer).toBeNull();
    expect(card.parecerPorName).toBeNull();
  });

  it("agentes ainda carregando: o autor do parecer fica undefined, nunca vira null afirmativo", () => {
    const card = resolveReviewCard(
      review({ parecer: "APROVARIA — ok", parecerPorAgentId: "ag-2" }),
      [issue()],
      undefined,
    );
    expect(card.parecerPorName).toBeUndefined();
  });

  it("review decidida por gestor promovido ganha o selo com o nome", () => {
    const card = resolveReviewCard(
      review({ status: "APPROVED", reviewerActorType: "AGENT", reviewerActorId: "ag-2" }),
      [issue()],
      [agent(), agent({ id: "ag-2", name: "Ana" })],
    );
    expect(card.decididaPorAgenteSelo).toBe("decidida · Ana");
  });

  it("gestor que sumiu do quadro ainda mostra que NÃO foi o dono que decidiu", () => {
    const card = resolveReviewCard(
      review({ status: "APPROVED", reviewerActorType: "AGENT", reviewerActorId: "ag-sumido" }),
      [issue()],
      [agent()],
    );
    expect(card.decididaPorAgenteSelo).toBe("decidida por gestor");
  });

  it("decisão do dono NÃO ganha selo — o selo marca o que fugiu do caminho normal", () => {
    const card = resolveReviewCard(
      review({ status: "APPROVED", reviewerActorType: "BOARD", reviewerActorId: "board-1" }),
      [issue()],
      [agent()],
    );
    expect(card.decididaPorAgenteSelo).toBeNull();
  });
});
