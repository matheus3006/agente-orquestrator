package com.orquestrador.work.issue;

/**
 * Resultado da liquidação de uma Issue por {@link IssueService#settleAfterRun} (ADR-0011).
 * {@code NOT_SETTLED} = o run não detinha a Issue (guard casou 0 linhas) → nada mudou (vítima intacta).
 */
public enum IssueSettlement {
    COMPLETED,
    // Review gate (ADR-0012): sucesso de alto risco não vira DONE — estacionou em PENDING_REVIEW.
    SUBMITTED_FOR_REVIEW,
    BLOCKED,
    NOT_SETTLED
}
