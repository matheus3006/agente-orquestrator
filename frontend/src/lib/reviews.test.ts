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
});
