package com.orquestrador.governance.approval;

/** O ciclo da Approval pré-hoc (ADR-0018, espelha a review): request→decision. */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
