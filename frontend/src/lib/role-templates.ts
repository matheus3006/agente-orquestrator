import type { Engine, RoleTemplate, RoleTemplateVersion, TipoFuncao } from "./api";
import { engineLabel, type Motor } from "./engines";

// Helpers PUROS dos moldes (Teams T.1, ADR-0017). O eixo FIXO (tipo de função) tem rótulo legível e ordem
// de pirâmide; os 2 últimos são RESERVADOS no v1 (revisor = board; suporte = KnowAll). O motor de uma
// versão resolve contra o catálogo /engines reusando o engineLabel — nunca quebra, nunca some.

/** Os 6 tipos de função em ordem de pirâmide (o dropdown de criar molde consome esta lista). */
export const TIPOS_FUNCAO: TipoFuncao[] = [
  "LIDERANCA",
  "GESTAO_PO",
  "PLANEJAMENTO_ARQUITETO",
  "EXECUCAO",
  "REVISAO_QA",
  "SUPORTE",
];

const TIPO_LABELS: Record<TipoFuncao, string> = {
  LIDERANCA: "Liderança",
  GESTAO_PO: "Gestão / PO",
  PLANEJAMENTO_ARQUITETO: "Planejamento / Arquiteto",
  EXECUCAO: "Execução",
  REVISAO_QA: "Revisão / QA",
  SUPORTE: "Suporte",
};

const RESERVADOS: ReadonlySet<TipoFuncao> = new Set<TipoFuncao>(["REVISAO_QA", "SUPORTE"]);

/** Rótulo legível do tipo de função; um valor desconhecido cai no próprio código (nunca some). */
export function tipoFuncaoLabel(tipo: TipoFuncao): string {
  return TIPO_LABELS[tipo] ?? tipo;
}

/** Reservado no v1 (sem agente próprio): a UI mostra mas marca/desabilita conforme o caso. */
export function isTipoReservado(tipo: TipoFuncao): boolean {
  return RESERVADOS.has(tipo);
}

/** O motor de uma versão como trio (valor do MotorSelect); trio todo-null → null (sem motor). */
export function motorDaVersao(versao: RoleTemplateVersion): Motor | null {
  if (!versao.adapterType && !versao.model && !versao.effort) {
    return null;
  }
  return {
    adapterType: versao.adapterType ?? "process",
    model: versao.model,
    effort: versao.effort,
  };
}

/**
 * O rótulo do motor de uma versão do molde, resolvido contra o catálogo /engines (reusa {@link engineLabel}).
 * Trio todo-null (molde sem motor definido) → "sem motor" (o hire cai no default do plano).
 */
export function versionMotorLabel(version: RoleTemplateVersion, engines: Engine[]): string {
  const motor = motorDaVersao(version);
  return motor ? engineLabel(motor, engines) : "sem motor";
}

/* ---------- savelog real (GET /versions) ---------- */

// Identidade dos 4 moldes de SISTEMA semeados pela V14 (diferencial #6, "time pronto de prateleira").
// Só a MARCA visual é client-side; preset e savelog vêm do GET /versions como em qualquer molde.
const MOLDES_DE_SISTEMA: ReadonlySet<string> = new Set([
  "0e1a0000-0000-4000-8000-000000000001", // architect
  "0e1a0000-0000-4000-8000-000000000002", // executor
  "0e1a0000-0000-4000-8000-000000000003", // product-owner
  "0e1a0000-0000-4000-8000-000000000004", // leader
]);

/** Molde de SISTEMA (diferencial #6): um dos 4 semeados pela V14. */
export function isMoldeDeSistema(template: RoleTemplate): boolean {
  return MOLDES_DE_SISTEMA.has(template.id);
}

/** A versão CORRENTE dentro do savelog — o painel "Preset" e o seed do dialog de edição leem dela. */
export function versaoCorrente(
  template: RoleTemplate,
  savelog: RoleTemplateVersion[],
): RoleTemplateVersion | null {
  return savelog.find((versao) => versao.id === template.currentVersionId) ?? null;
}

/** `value` é um dos 6 tipos de função? (o reports_to_default do seed usa o nome do enum) */
export function isTipoFuncao(value: string): value is TipoFuncao {
  return (TIPOS_FUNCAO as string[]).includes(value);
}

/**
 * Rótulo do reports_to_default: nome do enum vira rótulo legível; texto livre passa como veio
 * (o campo é tipo/role SUGERIDO — nunca agent id); null → sem sugestão (raiz da pirâmide).
 */
export function reportsToDefaultLabel(value: string | null): string {
  if (!value) {
    return "ninguém (raiz da pirâmide)";
  }
  return isTipoFuncao(value) ? tipoFuncaoLabel(value) : value;
}

/** Conhecimentos digitados no compositor: um por linha, aparados, sem vazios nem duplicados. */
export function linhasDeConhecimento(texto: string): string[] {
  const vistos = new Set<string>();
  const linhas: string[] = [];
  for (const linha of texto.split("\n")) {
    const aparada = linha.trim();
    if (aparada && !vistos.has(aparada.toLowerCase())) {
      vistos.add(aparada.toLowerCase());
      linhas.push(aparada);
    }
  }
  return linhas;
}
