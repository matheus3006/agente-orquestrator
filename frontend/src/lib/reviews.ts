import type { AgentSummary, IssueSummary, ReviewSummary } from "./api";
import { shortId } from "./format";

// Montagem do card da fila de review. A ReviewResponse é enxuta (só o essencial
// do ciclo): título/risco/assignee vêm da lista de issues, e o nome do assignee
// da lista de agentes. Degrada, nunca quebra — issue não resolvida → fallback de
// título + risco null (sem badge). A semântica null/undefined do assignee é a
// MESMA da página de Issues: undefined = ainda carregando ("—", nunca afirma
// livre para uma issue designada); null = resolvido e sem assignee ("— livre").

export interface ReviewCard {
  reviewId: string;
  issueId: string;
  runId: string;
  status: string;
  title: string;
  riskLevel: number | null;
  assigneeName: string | null | undefined;
  decisionReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
}

export function resolveReviewCard(
  review: ReviewSummary,
  issues: IssueSummary[] | undefined,
  agents: AgentSummary[] | undefined,
): ReviewCard {
  const issue = issues?.find((candidate) => candidate.id === review.issueId);
  return {
    reviewId: review.id,
    issueId: review.issueId,
    runId: review.runId,
    status: review.status,
    title: issue?.title ?? `issue ${shortId(review.issueId)}`,
    riskLevel: issue?.riskLevel ?? null,
    assigneeName: resolveAssigneeName(issue, agents),
    decisionReason: review.decisionReason,
    requestedAt: review.requestedAt,
    decidedAt: review.decidedAt,
  };
}

function resolveAssigneeName(
  issue: IssueSummary | undefined,
  agents: AgentSummary[] | undefined,
): string | null | undefined {
  if (!issue) {
    return undefined;
  }
  if (!issue.assigneeAgentId) {
    return null;
  }
  if (!agents) {
    return undefined;
  }
  return agents.find((agent) => agent.id === issue.assigneeAgentId)?.name ?? null;
}
