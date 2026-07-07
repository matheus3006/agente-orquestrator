import type { AgentBoardStatus, IssueSummary, MissionControlAgent } from "./api";
import { isTerminalIssueStatus } from "./issues";

// Mapa status→apresentação do Mission Control — a spec do protótipo. O status
// já vem DERIVADO do servidor (precedência RUNNING > QUEUED > BLOCKED > IDLE);
// aqui é só vocabulário visual.

const STATUS_WORD: Record<AgentBoardStatus, string> = {
  RUNNING: "rodando",
  QUEUED: "na fila",
  BLOCKED: "bloqueado",
  IDLE: "ocioso",
};

const ISSUE_LINE_VERB: Record<AgentBoardStatus, string> = {
  RUNNING: "executando",
  QUEUED: "na fila para",
  BLOCKED: "travado em",
  IDLE: "última issue:",
};

export function statusWord(status: AgentBoardStatus): string {
  return STATUS_WORD[status];
}

/** A classe CSS de status (led/card/status-word compartilham). */
export function statusClass(status: AgentBoardStatus): string {
  return status.toLowerCase();
}

export function issueLineVerb(status: AgentBoardStatus): string {
  return ISSUE_LINE_VERB[status];
}

export interface TallyCounts {
  running: number;
  queued: number;
  blocked: number;
  idle: number;
}

/** Tallies derivados do agregado — a tela nunca conta por conta própria. */
export function tallyCounts(agents: MissionControlAgent[]): TallyCounts {
  const counts: TallyCounts = { running: 0, queued: 0, blocked: 0, idle: 0 };
  for (const station of agents) {
    counts[station.status.toLowerCase() as keyof TallyCounts] += 1;
  }
  return counts;
}

/**
 * Qual run o drawer do card abre: o ativo, senão o run do rastro (lastSteps
 * vem do run mais recente — card ocioso mantém a história); sem nada → null.
 */
export function cardRunId(station: MissionControlAgent): string | null {
  return station.activeRun?.id ?? station.lastSteps.at(-1)?.runId ?? null;
}

export interface StationIssueLine {
  /** Nota em itálico quando não há issue a mostrar (idle-note do protótipo). */
  note: string | null;
  verb: string | null;
  title: string | null;
}

/**
 * A linha de issue do card. RUNNING/QUEUED têm o título no próprio agregado
 * (activeRun.issueTitle); BLOCKED/IDLE resolvem pela lista de issues que a
 * tela já tem (a issue BLOCKED designada / a designada mais recente).
 */
export function stationIssueLine(
  station: MissionControlAgent,
  issues: IssueSummary[],
): StationIssueLine {
  const agentId = station.agent.id;
  if (station.status === "RUNNING" || station.status === "QUEUED") {
    const title = station.activeRun?.issueTitle ?? null;
    return title
      ? { note: null, verb: issueLineVerb(station.status), title }
      : { note: "rotina interna", verb: null, title: null };
  }
  if (station.status === "BLOCKED") {
    const blocked = issues.find(
      (issue) => issue.assigneeAgentId === agentId && issue.status === "BLOCKED",
    );
    return blocked
      ? { note: null, verb: issueLineVerb("BLOCKED"), title: blocked.title }
      : { note: "rotina interna", verb: null, title: null };
  }
  const assigned = issues.filter((issue) => issue.assigneeAgentId === agentId);
  if (assigned.length === 0) {
    return { note: "disponível — sem issue designada", verb: null, title: null };
  }
  const latest = assigned.reduce((a, b) => (a.createdAt >= b.createdAt ? a : b));
  return { note: null, verb: issueLineVerb("IDLE"), title: latest.title };
}

/**
 * O agregado (poll de 2s) revelou um agente BLOCKED que a lista de issues
 * (staleTime 30s) ainda não conhece — é o gatilho para invalidar a lista
 * (senão o card mostra "rotina interna" até um refocus; achado da revisão).
 */
export function issuesListBehindAggregate(
  agents: MissionControlAgent[],
  issues: IssueSummary[],
): boolean {
  return agents.some(
    (station) =>
      station.status === "BLOCKED" &&
      !issues.some(
        (issue) => issue.assigneeAgentId === station.agent.id && issue.status === "BLOCKED",
      ),
  );
}

/** Parado = nenhum agente RUNNING/QUEUED/BLOCKED (só existem esses 4 status). */
function boardParado(agents: MissionControlAgent[]): boolean {
  return agents.every((station) => station.status === "IDLE");
}

/** O próximo passo canônico quando o board está parado — ou null quando não é
 *  o momento de guiar. */
export type EstadoOcioso = "primeiro-uso" | "entregue" | "quieto";

/**
 * #12 (P2-C): com o board parado a identidade fósforo apaga — a tela precisa
 * apontar o próximo passo. Função PURA: recebe `agents`/`issues` (NÃO tally)
 * aceitando `undefined` e retornando `null` — assim o call-site não pode lavar
 * o `undefined` com `?? []` e classificar "primeiro-uso" FALSO numa company com
 * issues ainda em voo (a race de load da page.tsx). Retorna `null` também sem
 * CEO (o composer já avisa) e quando há qualquer atividade — BLOCKED inclusive,
 * pois aí o próximo passo já é outro (resolver o bloqueio), não dar direção nova.
 * `primeiro-uso` = zero issues; `entregue` = todas terminais; `quieto` = há issue
 * não-terminal (a copy de `quieto` aponta a pendência quando é PENDING_REVIEW).
 */
export function estadoOcioso(
  agents: MissionControlAgent[] | undefined,
  ceoNome: string | null,
  issues: IssueSummary[] | undefined,
): EstadoOcioso | null {
  if (!agents || !issues) {
    return null;
  }
  if (ceoNome === null) {
    return null;
  }
  if (!boardParado(agents)) {
    return null;
  }
  if (issues.length === 0) {
    return "primeiro-uso";
  }
  if (issues.every((issue) => isTerminalIssueStatus(issue.status))) {
    return "entregue";
  }
  return "quieto";
}

/**
 * O agregado (poll 2s) já viu o board PARAR, mas a lista de issues (staleTime
 * 30s) ainda tem a issue IN_PROGRESS — sem invalidar, "entregue" nunca chega e
 * "quieto" mente indefinidamente. Espelho do issuesListBehindAggregate (F.3),
 * com o mesmo guard `issuesUpdatedAt < snapshotUpdatedAt` no call-site. Restrito
 * a IN_PROGRESS: estados estacionários (OPEN/PENDING_REVIEW) mudam por ação do
 * operador, não por settle — invalidar neles seria refetch à toa.
 */
export function issuesListBehindIdleSettle(
  agents: MissionControlAgent[],
  issues: IssueSummary[],
): boolean {
  return boardParado(agents) && issues.some((issue) => issue.status === "IN_PROGRESS");
}
