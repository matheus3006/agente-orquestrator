import type { Approval, ApprovalActionType, BatchDecisionItem, BatchDecisionOutcome } from "@/lib/api";

// A fila dedicada de aprovação em lote (ADR-0035, D1–D3/D8/D9): a tela
// /approvals projeta ESTA lógica pura — nenhuma regra de lote vive em
// componente. O lote é homogêneo por tipo POR CONSTRUÇÃO (D2): os botões só
// habilitam com filtro de tipo ativo, e os itens do POST saem das VISÍVEIS
// sob o filtro — selecionada de outro tipo nunca viaja.

export type FiltroTipo = ApprovalActionType | "todos";

/**
 * Os filtros de tipo na ordem da dor real (a cascata dos 27 era HIRE_AGENT). Completo sobre o
 * enum desde a fatia dirigir-sem-gate (ADR-0047): estava 4-de-7 defasado — tipo sem filtro não
 * podia ser decidido em lote (o lote exige filtro ativo, D2).
 */
export const FILTRO_TIPOS: ReadonlyArray<{ id: FiltroTipo; label: string }> = [
  { id: "todos", label: "todos" },
  { id: "HIRE_AGENT", label: "contratações" },
  { id: "CREATE_ISSUE", label: "issues" },
  { id: "WAKEUP_AGENT", label: "wakeups" },
  { id: "CREATE_TEAM", label: "times" },
  { id: "CREATE_ROLE_TEMPLATE", label: "moldes" },
  { id: "SKILL_INSTALL_REQUEST", label: "skills" },
  { id: "GRADUATE_PRINCIPLE", label: "princípios" },
  { id: "DIRECT_ISSUE", label: "direcionamentos" },
];

/** D2: agir em lote exige filtro por tipo ativo — misturar contratação com issue num clique, não. */
export function loteHabilitado(filtro: FiltroTipo, selecionadas: number): boolean {
  return filtro !== "todos" && selecionadas > 0;
}

export function filtrarPorTipo(approvals: Approval[], filtro: FiltroTipo): Approval[] {
  return filtro === "todos"
    ? approvals
    : approvals.filter((approval) => approval.actionType === filtro);
}

/** Selecionar tudo alterna sobre as VISÍVEIS: tudo marcado limpa; qualquer buraco completa. */
export function alternarSelecaoTotal(
  visiveis: Approval[],
  selecionadas: ReadonlySet<string>,
): Set<string> {
  const tudoMarcado = visiveis.length > 0 && visiveis.every((approval) => selecionadas.has(approval.id));
  return tudoMarcado ? new Set() : new Set(visiveis.map((approval) => approval.id));
}

/** Os itens do POST na ordem da fila; observação por item só quando preenchida (D8). */
export function itensDoLote(
  visiveis: Approval[],
  selecionadas: ReadonlySet<string>,
  observacoes: Readonly<Record<string, string>>,
): BatchDecisionItem[] {
  return visiveis
    .filter((approval) => selecionadas.has(approval.id))
    .map((approval) => {
      const observacao = observacoes[approval.id]?.trim();
      return observacao ? { approvalId: approval.id, observacao } : { approvalId: approval.id };
    });
}

/** O resumo do toast: a contagem vem da resposta (D9) — pulada não é erro, é fila viva. */
export function resumoDoLote(
  outcome: Pick<BatchDecisionOutcome, "decided" | "skipped">,
  decision: "APPROVE" | "REJECT",
): string {
  const decididas =
    decision === "APPROVE"
      ? `${outcome.decided} aprovada${outcome.decided === 1 ? "" : "s"}`
      : `${outcome.decided} recusada${outcome.decided === 1 ? "" : "s"}`;
  if (outcome.skipped.length > 0) {
    const puladas = `${outcome.skipped.length} pulada${outcome.skipped.length === 1 ? "" : "s"}`;
    return `${decididas} · ${puladas} (já decidida, grelhada ou com alvo quebrado)`;
  }
  return decision === "REJECT" ? `${decididas} — o CEO recebe o motivo na conversa` : decididas;
}
