package com.orquestrador.work.review;

/**
 * A decisão do board sobre uma Issue estacionada em PENDING_REVIEW (ADR-0012):
 * APPROVE → DONE; REJECT → BLOCKED (o recovery é o unblock do §7/ADR-0011).
 */
public enum ReviewDecision {
    APPROVE,
    REJECT
}
