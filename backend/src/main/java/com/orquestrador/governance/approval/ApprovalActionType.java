package com.orquestrador.governance.approval;

/**
 * As ações que o CEO pode PROPOR no v1 (ADR-0018 — o APPROVE dispara a ação real com
 * ator=aprovador): contratar, criar Issue, acordar um agente. O vocabulário cresce por fatia
 * (molde global = T.1 governança; spawn/fan-out = T.4) — enum fechado é o deny-by-default.
 */
public enum ApprovalActionType {
    HIRE_AGENT,
    CREATE_ISSUE,
    WAKEUP_AGENT
}
