package com.orquestrador.work.review;

/**
 * Ciclo request→decision de uma {@link Review} (ADR-0012). Nasce PENDING no gate;
 * o board decide APPROVED ou REJECTED (R.3). Persistido como text.
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
