package com.orquestrador.execution;

import org.springframework.stereotype.Service;

/**
 * Recupera Runs orfaos: aqueles cujo worker morreu e o lease expirou voltam para a fila.
 * Idempotente — rodar varias vezes nao causa dano.
 */
@Service
public class LeaseReaper {

    private final HeartbeatRunStore store;

    public LeaseReaper(HeartbeatRunStore store) {
        this.store = store;
    }

    public int reapOnce() {
        return store.reapExpired();
    }
}
