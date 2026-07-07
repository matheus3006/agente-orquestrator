package com.orquestrador.execution;

public enum WakeupStatus {
    /** Recem-criado, ainda nao ligado a um Run. */
    PENDING,
    /** Mesclado num Run que ainda nao comecou (sera atendido por ele). */
    COALESCED,
    /** Chegou com o Run ja em execucao; sera atendido por um Run seguinte (promote-on-finalize). */
    DEFERRED,
    /** Cancelado por hard-stop de budget. */
    CANCELLED
}
