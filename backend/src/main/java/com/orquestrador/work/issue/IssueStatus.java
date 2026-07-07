package com.orquestrador.work.issue;

public enum IssueStatus {
    OPEN,
    IN_PROGRESS,
    // Review gate (ADR-0012): run de alto risco concluiu, aguarda decisão do board
    // (APPROVE→DONE / REJECT→BLOCKED). Fora do conjunto pegável: não re-roda sozinha.
    PENDING_REVIEW,
    BLOCKED,
    DONE,
    CANCELLED
}
