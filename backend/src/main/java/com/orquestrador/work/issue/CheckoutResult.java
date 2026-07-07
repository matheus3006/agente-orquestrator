package com.orquestrador.work.issue;

import java.util.UUID;

/**
 * Resultado de um checkout atomico. {@code acquired=false} significa 409 (nao pegou) —
 * o chamador NUNCA deve fazer retry automatico de um conflito.
 */
public record CheckoutResult(boolean acquired, Issue issue, UUID runId) {

    public static CheckoutResult acquired(Issue issue, UUID runId) {
        return new CheckoutResult(true, issue, runId);
    }

    public static CheckoutResult conflict() {
        return new CheckoutResult(false, null, null);
    }
}
