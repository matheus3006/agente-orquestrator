import type { ActivityEvent, IssueRun, IssueSummary } from "./api";
import { fmtAgo, fmtElapsed } from "./format";
import { isTerminalRunStatus } from "./transcript";

// Vocabulário da superfície Issues — a spec do protótipo (STATUS_ORDER/
// STATUS_LABEL/STATUS_LED de app.js) + os derivados puros que a tela consome.

export const ISSUE_STATUS_ORDER = [
  "IN_PROGRESS",
  "PENDING_REVIEW",
  "BLOCKED",
  "OPEN",
  "DONE",
  "CANCELLED",
] as const;

const STATUS_LABEL: Record<string, string> = {
  IN_PROGRESS: "Em execução",
  PENDING_REVIEW: "Aguardando review",
  BLOCKED: "Bloqueadas",
  OPEN: "Abertas",
  DONE: "Concluídas",
  CANCELLED: "Canceladas",
};

const STATUS_LED: Record<string, string> = {
  IN_PROGRESS: "running",
  PENDING_REVIEW: "queued",
  BLOCKED: "blocked",
  OPEN: "",
  DONE: "",
  CANCELLED: "",
};

export function issueStatusLabel(status: string): string {
  return STATUS_LABEL[status] ?? status;
}

/** Classe do LED por status de issue ("" = LED apagado, cinza idle). */
export function issueStatusLed(status: string): string {
  return STATUS_LED[status] ?? "";
}

/**
 * Issue terminal = trabalho encerrado (concluída ou cancelada). Risco é sobre
 * trabalho PENDENTE — nesses estados o badge de risco muta a cor de perigo (#14).
 */
export function isTerminalIssueStatus(status: string): boolean {
  return status === "DONE" || status === "CANCELLED";
}

/**
 * O selo da task corretiva (caso B, ADR-0034): "corrige #N" torna a reincidência
 * VISÍVEL na superfície — o racional do B2. Null = task normal (sem selo); alvo
 * colhido (corrigeSeq null) também se cala — a coluna segue auditável no backend.
 */
export function corrigeSelo(issue: IssueSummary): string | null {
  return issue.corrigeSeq === null ? null : `corrige #${issue.corrigeSeq}`;
}

/**
 * O selo da issue DIRIGIDA SEM GATE (ADR-0047, D-DSG-6): "dirigida · <gestor>" — bater o olho na
 * lista e ver o que veio de gestor promovido sem abrir nada. Null = sem selo (issue normal E a do
 * ramo estágio, que passou pelo gate). O nome resolve no MESMO mapa de agentes do AssigneeChip:
 * `undefined` (agents carregando/falhou) mostra o selo sem afirmar o nome.
 */
export function dirigidaSelo(
  issue: IssueSummary,
  gestorNome: string | null | undefined,
): string | null {
  if (issue.dirigidaPorGestorAgentId === null) {
    return null;
  }
  return gestorNome ? `dirigida · ${gestorNome}` : "dirigida";
}

export interface IssueGroup {
  status: string;
  label: string;
  led: string;
  issues: IssueSummary[];
}

/**
 * Grupos na ordem do protótipo, vazios omitidos, mais recente primeiro dentro
 * do grupo. Status fora do vocabulário (cresce por fatia) vira grupo próprio
 * no fim — issue nunca some da lista.
 */
export function groupIssuesByStatus(issues: IssueSummary[]): IssueGroup[] {
  const byStatus = new Map<string, IssueSummary[]>();
  for (const issue of issues) {
    const bucket = byStatus.get(issue.status) ?? [];
    bucket.push(issue);
    byStatus.set(issue.status, bucket);
  }
  const knownOrder = ISSUE_STATUS_ORDER.filter((status) => byStatus.has(status));
  const unknown = [...byStatus.keys()].filter(
    (status) => !(ISSUE_STATUS_ORDER as readonly string[]).includes(status),
  );
  return [...knownOrder, ...unknown].map((status) => ({
    status,
    label: issueStatusLabel(status),
    led: issueStatusLed(status),
    // seq desc ≡ createdAt desc (alocação monotônica, ADR-0033) — mas com o #N visível
    // na linha, a ordem TEM que ser a dos números (a mesma do estado vivo do CEO).
    issues: (byStatus.get(status) ?? []).toSorted((a, b) => b.seq - a.seq),
  }));
}

/** A nota ao lado do risk badge no props panel (spec do protótipo). */
export function riskNote(riskLevel: number): string {
  if (riskLevel >= 5) {
    return "review obrigatória";
  }
  if (riskLevel >= 3) {
    return "atenção";
  }
  return "baixo";
}

/**
 * Eventos do feed que pertencem à issue — por ID (entity issue OU
 * detail.issueId, caso do SKILL_ATTACHED), nunca por casamento de texto.
 */
export function issueActivityEvents(events: ActivityEvent[], issueId: string): ActivityEvent[] {
  return events.filter(
    (event) =>
      (event.entityType === "issue" && event.entityId === issueId) ||
      event.detail?.issueId === issueId,
  );
}

/**
 * O ledger (poll 2s) enxergou o settle antes da lista de issues (staleTime
 * 30s): issue ainda IN_PROGRESS com todos os runs terminais → invalidar a
 * lista (o espelho do issuesListBehindAggregate da F.3; achado da revisão).
 */
export function issueBehindLedger(issue: IssueSummary, runs: IssueRun[]): boolean {
  return (
    issue.status === "IN_PROGRESS" &&
    runs.length > 0 &&
    runs.every((run) => isTerminalRunStatus(run.status))
  );
}

/**
 * Quando o poll de 2s do ledger continua: run vivo no ledger OU issue viva
 * (OPEN/IN_PROGRESS — o próximo run precisa aparecer sem blur/refocus).
 * Issue estacionada (BLOCKED/PENDING_REVIEW/DONE/CANCELLED) com ledger
 * terminal muda só por ação do operador → invalidação cobre.
 */
export function ledgerKeepsPolling(issueStatus: string, runs: IssueRun[]): boolean {
  return (
    runs.some((run) => !isTerminalRunStatus(run.status)) ||
    issueStatus === "OPEN" ||
    issueStatus === "IN_PROGRESS"
  );
}

/** A coluna de tempo da linha do ledger (spec do protótipo). */
export function ledgerWhen(run: IssueRun, nowMs: number): string {
  if (!run.startedAt) {
    return isTerminalRunStatus(run.status) ? fmtAgo(run.createdAt, nowMs) : "aguardando dispatch";
  }
  if (!run.finishedAt) {
    return `${fmtElapsed(nowMs - Date.parse(run.startedAt))} em voo`;
  }
  return `${fmtAgo(run.startedAt, nowMs)} · ${fmtElapsed(
    Date.parse(run.finishedAt) - Date.parse(run.startedAt),
  )}`;
}
