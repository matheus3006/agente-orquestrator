// As projeções puras da coluna docada do CEO (T.3-FE, ADR-0018/0020) — o
// módulo profundo entre o shape dos endpoints e a thread renderizada:
// a tela projeta, o backend impõe. Nada aqui muta nem busca; só deriva.

import type { Approval, ConversationTurn, IssueSummary } from "./api";
import { shortId } from "./format";

/** O bloco de proposta na SAÍDA do CEO (o mesmo fence que o ProposalScanner lê). */
const PROPOSTA_FENCE = /```proposta\s*\n[\s\S]*?```/g;

export interface ReplySplit {
  prosa: string;
  temProposta: boolean;
}

/**
 * A bolha mostra SÓ a prosa: o bloco ```proposta é mecanismo (virou Approval
 * no backend) — o conteúdo legível dele chega pelo CARD ancorado, nunca como
 * JSON cru na conversa (spec do mockup §4).
 */
export function splitReply(ceoReply: string): ReplySplit {
  const temProposta = PROPOSTA_FENCE.test(ceoReply);
  PROPOSTA_FENCE.lastIndex = 0;
  const prosa = ceoReply.replace(PROPOSTA_FENCE, "").trim();
  return { prosa, temProposta };
}

/* ---------- a thread com approvals ancoradas (mockup §4) ---------- */

export type ThreadItem =
  | { kind: "turno"; turno: ConversationTurn }
  | { kind: "approval"; approval: Approval };

/**
 * Intercala a MESMA fila de approvals na thread, no ponto do tempo em que cada
 * uma surgiu: depois do turno cujo Run a propôs (runId). Órfã (run fora da
 * janela da thread) não some — cai no fim, em ordem de chegada.
 */
export function projetarThread(
  turnos: ConversationTurn[],
  approvals: Approval[],
): ThreadItem[] {
  const porChegada = [...approvals].sort((a, b) => a.requestedAt.localeCompare(b.requestedAt));
  const runsNaThread = new Set(turnos.map((turno) => turno.runId).filter(Boolean));

  const itens: ThreadItem[] = [];
  for (const turno of turnos) {
    itens.push({ kind: "turno", turno });
    for (const approval of porChegada) {
      if (approval.runId !== null && approval.runId === turno.runId) {
        itens.push({ kind: "approval", approval });
      }
    }
  }
  for (const approval of porChegada) {
    if (approval.runId === null || !runsNaThread.has(approval.runId)) {
      itens.push({ kind: "approval", approval });
    }
  }
  return itens;
}

/* ---------- a animação da thread: só o que o polling APENDA anima ---------- */

/**
 * A chave de animação de cada item — a IDENTIDADE que o congelamento da 1ª
 * pintura completa rastreia. O turno leva o status (PENDING→ANSWERED conta como
 * item novo, e re-anima); a approval leva só o id. Mesma ordem da projeção.
 */
export function chavesDaThread(itens: ThreadItem[]): string[] {
  return itens.map((item) =>
    item.kind === "turno" ? `t:${item.turno.id}:${item.turno.status}` : `a:${item.approval.id}`,
  );
}

/**
 * Quais itens estão ENTRANDO (devem animar), dado o conjunto de chaves congelado
 * na 1ª pintura completa — ou null, antes dela. Antes de congelar nada anima;
 * depois, só o que caiu FORA do congelado (o que o polling apendou). Paralelo a
 * `chaves`: o índice i responde pelo item i. A DECISÃO é pura; o ESTADO — quando
 * congelar — é intrínseco ao render e fica no componente.
 */
export function entrandoNaThread(
  chaves: string[],
  chavesCongeladas: Set<string> | null,
): boolean[] {
  if (chavesCongeladas === null) {
    return chaves.map(() => false);
  }
  return chaves.map((chave) => !chavesCongeladas.has(chave));
}

/* ---------- o alvo humanizado do card (mockup §4: nunca JSON cru) ---------- */

export interface AcaoLegivel {
  verbo: string;
  /** null quando o alvo não dá para humanizar (JSON quebrado) — o card cai no resumo. */
  alvo: string | null;
}

const VERBO_POR_ACAO: Record<Approval["actionType"], string> = {
  CREATE_ISSUE: "Abrir issue",
  HIRE_AGENT: "Contratar agente",
  WAKEUP_AGENT: "Acordar agente",
};

/** Parse fail-soft do targetDetail — espelha o ProposalScanner: quebrado nunca derruba a tela. */
function alvoDe(approval: Approval): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(approval.targetDetail);
    return typeof parsed === "object" && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export function acaoLegivel(approval: Approval): AcaoLegivel {
  const verbo = VERBO_POR_ACAO[approval.actionType];
  const alvo = alvoDe(approval);
  if (!alvo) {
    return { verbo, alvo: null };
  }
  switch (approval.actionType) {
    case "CREATE_ISSUE":
      return { verbo, alvo: typeof alvo.title === "string" ? alvo.title : null };
    case "HIRE_AGENT": {
      const nome = typeof alvo.name === "string" ? alvo.name : null;
      const role = typeof alvo.role === "string" ? alvo.role : null;
      return { verbo, alvo: nome ? (role ? `${nome} · ${role}` : nome) : null };
    }
    case "WAKEUP_AGENT":
      return { verbo, alvo: typeof alvo.agentId === "string" ? shortId(alvo.agentId) : null };
  }
}

/**
 * A issue que o APPROVE de CREATE_ISSUE criou, pro disclosure do report do
 * Ledger. O id criado vive no activity_log (janela finita) — aqui resolve pelo
 * TÍTULO exato contra a lista completa; empate fica com a mais recente
 * (aprovar duas vezes o mesmo título cria duas issues — limitação v1 aceita).
 */
export function issueDaApproval(approval: Approval, issues: IssueSummary[]): IssueSummary | null {
  if (approval.actionType !== "CREATE_ISSUE" || approval.status !== "APPROVED") {
    return null;
  }
  const titulo = acaoLegivel(approval).alvo;
  if (!titulo) {
    return null;
  }
  const candidatas = issues.filter((issue) => issue.title === titulo);
  if (candidatas.length === 0) {
    return null;
  }
  return candidatas.reduce((a, b) => (a.createdAt >= b.createdAt ? a : b));
}

/* ---------- o badge da aba (mockup §3) ---------- */

/**
 * Quantas approvals esperam o board, por conversa — a MESMA fila do gate
 * projetada como badge. Proposta fora de conversa (conversationId null) não
 * pertence a aba nenhuma.
 */
export function pendentesPorConversa(fila: Approval[]): Map<string, number> {
  const contagem = new Map<string, number>();
  for (const approval of fila) {
    if (approval.status !== "PENDING" || approval.conversationId === null) {
      continue;
    }
    contagem.set(approval.conversationId, (contagem.get(approval.conversationId) ?? 0) + 1);
  }
  return contagem;
}
