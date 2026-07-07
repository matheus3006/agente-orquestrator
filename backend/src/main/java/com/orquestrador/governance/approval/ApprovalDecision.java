package com.orquestrador.governance.approval;

/** A decisão do board sobre uma proposta do CEO (ADR-0018): APPROVE dispara a ação real; REJECT arquiva. */
public enum ApprovalDecision {
    APPROVE,
    REJECT
}
