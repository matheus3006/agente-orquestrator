package com.orquestrador.governance.approval;

/**
 * As ações que o CEO pode PROPOR no v1 (ADR-0018 — o APPROVE dispara a ação real com
 * ator=aprovador): contratar, criar Issue, acordar um agente, criar time. O vocabulário cresce
 * por fatia (molde global = T.1 governança; spawn/fan-out = T.4) — enum fechado é o
 * deny-by-default. {@code CREATE_TEAM} é o alargamento DELIBERADO da ADR-0037 (D7): time +
 * arquiteto atômico — o time do gate nasce com arquiteto por construção; executores vêm depois,
 * como {@code HIRE_AGENT} individuais (aprovação incremental da ADR-0017 intocada).
 */
public enum ApprovalActionType {
    HIRE_AGENT,
    CREATE_ISSUE,
    WAKEUP_AGENT,
    CREATE_TEAM
}
