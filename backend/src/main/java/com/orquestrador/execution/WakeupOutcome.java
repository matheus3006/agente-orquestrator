package com.orquestrador.execution;

import java.util.UUID;

/** Resultado de um enqueue de wakeup. {@code created=false} significa que coalesceu num Run existente. */
public record WakeupOutcome(UUID runId, boolean created) {
}
