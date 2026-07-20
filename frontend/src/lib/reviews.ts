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
  /** O #N humano da issue (ADR-0033); null enquanto a issue não resolve. */
  seq: number | null;
  title: string;
  riskLevel: number | null;
  assigneeName: string | null | undefined;
  decisionReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
  /** F-C1 (C11): a área do ARTEFATO (time do assignee); null se a issue sumiu (degrada). */
  area: string | null;
  /** LOGICO | INTUICAO derivado da área (D9) — INTUICAO = a maiêutica (D10) no prompt. */
  racionalTipo: string | null;
  /** A pergunta não-bloqueante do approve, nas PALAVRAS da política (C14). */
  racionalProbe: string | null;
  /** F-ORG-C (ADR-0048): o parecer consultivo do gestor, mostrado AO LADO das ações do dono. */
  parecer: string | null;
  /** Nome de quem opinou; null = sem parecer OU agente sumido; undefined = agentes carregando. */
  parecerPorName: string | null | undefined;
  parecerEm: string | null;
  /** "decidida · <gestor>" quando quem carimbou foi um AGENT; null = decisão do dono (sem selo). */
  decididaPorAgenteSelo: string | null;
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
    seq: issue?.seq ?? null,
    title: issue?.title ?? `issue ${shortId(review.issueId)}`,
    riskLevel: issue?.riskLevel ?? null,
    assigneeName: resolveAssigneeName(issue, agents),
    decisionReason: review.decisionReason,
    requestedAt: review.requestedAt,
    decidedAt: review.decidedAt,
    area: review.area ?? null,
    racionalTipo: review.racionalTipo ?? null,
    racionalProbe: review.racionalProbe ?? null,
    parecer: review.parecer ?? null,
    parecerPorName: resolveAgentName(review.parecerPorAgentId ?? null, agents),
    parecerEm: review.parecerEm ?? null,
    decididaPorAgenteSelo: decididaPorAgenteSelo(review, agents),
  };
}

/**
 * O selo da review decidida por um gestor PROMOVIDO (ADR-0048), irmão do `dirigidaSelo` do
 * PR #48: "decidida · <gestor>". `null` = carimbo do dono, que não ganha selo — o selo marca o
 * que fugiu do caminho normal, não o caminho normal. O nome degrada como no precedente: agente
 * que sumiu do quadro ainda mostra "decidida", porque o FATO de não ter sido o dono é a
 * informação que não pode se perder.
 */
function decididaPorAgenteSelo(
  review: ReviewSummary,
  agents: AgentSummary[] | undefined,
): string | null {
  if (review.reviewerActorType !== "AGENT") {
    return null;
  }
  const nome = resolveAgentName(review.reviewerActorId ?? null, agents);
  return nome ? `decidida · ${nome}` : "decidida por gestor";
}

// Mesma semântica null/undefined do assignee: undefined = ainda carregando (nunca afirma
// "sumiu" para um agente que existe); null = resolvido e sem nome a mostrar.
function resolveAgentName(
  agentId: string | null,
  agents: AgentSummary[] | undefined,
): string | null | undefined {
  if (!agentId) {
    return null;
  }
  if (!agents) {
    return undefined;
  }
  return agents.find((agent) => agent.id === agentId)?.name ?? null;
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
