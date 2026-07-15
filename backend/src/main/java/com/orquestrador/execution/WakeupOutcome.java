package com.orquestrador.execution;

import java.util.UUID;

/**
 * Resultado de um enqueue de wakeup. {@code created=false} significa que coalesceu num Run
 * existente; {@code blocked=true} significa que o circuit-breaker (ADR-0018 teto (c), R18-B)
 * barrou o re-enfileiramento AUTOMATICO de um par agente×issue travado — nenhum Run nasceu, e o
 * dono foi avisado (M6). A mao humana direta (root) nunca chega aqui: ela fura o breaker.
 */
public record WakeupOutcome(UUID runId, boolean created, boolean blocked) {

    /** Coalescing/criacao normal (compat com os call-sites de 2 args). */
    public WakeupOutcome(UUID runId, boolean created) {
        this(runId, created, false);
    }

    /** O circuit-breaker barrou: par travado, wakeup automatico — nada criado. */
    public static WakeupOutcome barrado() {
        return new WakeupOutcome(null, false, true);
    }
}
