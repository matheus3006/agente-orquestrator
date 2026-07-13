package com.orquestrador.governance.approval;

/**
 * As ações que o CEO pode PROPOR no v1 (ADR-0018 — o APPROVE dispara a ação real com
 * ator=aprovador): contratar, criar Issue, acordar um agente, criar time, criar molde, pedir
 * skill. O vocabulário cresce por fatia — enum fechado é o deny-by-default. {@code CREATE_TEAM} é
 * o alargamento DELIBERADO da ADR-0037 (D7): time + arquiteto atômico. {@code CREATE_ROLE_TEMPLATE}
 * é o alargamento da R10-A (propose-then-commit de molde, ADR-0017 emendada): o CEO propõe um
 * preset de cargo GLOBAL e o dono aprova — gate por ALCANCE (mexer no catálogo global = gate). A
 * versão do molde nasce só no APPROVE (proposedBy=CEO / approvedBy=dono); enquanto PENDING nada
 * persiste (o gate É o teto, G5). Editar molde existente segue mão-soberana-direta (G6, v1.1 pro CEO).
 * {@code SKILL_INSTALL_REQUEST} é o alargamento da F-AT1 (grill agente-como-tool, ADR-0003
 * emendada): o EXECUTOR de uma Issue pede uma skill fora do seu set pelo canal de saída (bloco
 * {@code pedido-skill}); o APPROVE anexa via {@code SkillSetService.attachToIssue} (install-on-
 * attach reusado, ADR-0003) — 1º consumidor real da Approval-de-ação-de-agente (ADR-0006).
 */
public enum ApprovalActionType {
    HIRE_AGENT,
    CREATE_ISSUE,
    WAKEUP_AGENT,
    CREATE_TEAM,
    CREATE_ROLE_TEMPLATE,
    SKILL_INSTALL_REQUEST
}
