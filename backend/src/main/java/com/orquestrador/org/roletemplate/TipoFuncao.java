package com.orquestrador.org.roletemplate;

/**
 * Tipo de função de um agente — o EIXO FIXO do design de roles (ADR-0017): o enum é o esqueleto que
 * segura os invariantes de composição (arquiteto obrigatório, cadeia de comando); a role específica
 * (catálogo aberto, {@code role_template.role_especifica}) é a "carne" com skills.
 *
 * <p>Os 6 valores são decisão de design do grilling A.3 ({@code handoff-grilling-teams-fechado.md}),
 * não pesquisa empírica.
 * Guardado como {@code text} ({@code @Enumerated(STRING)}) — padrão da casa ({@code IssueStatus}/
 * {@code RunStatus} são text; sem pg-enum).
 *
 * <p><b>Reservados no v1:</b> {@link #REVISAO_QA} (o revisor é o board/humano — ADR-0012) e
 * {@link #SUPORTE} (é o KnowAll). São ACEITOS no enum, mas não têm agente próprio nesta fatia.
 */
public enum TipoFuncao {
    LIDERANCA,
    GESTAO_PO,
    PLANEJAMENTO_ARQUITETO,
    EXECUCAO,
    /** Reservado no v1: o revisor é o board/humano (ADR-0012). */
    REVISAO_QA,
    /** Reservado no v1: o suporte é o KnowAll. */
    SUPORTE
}
