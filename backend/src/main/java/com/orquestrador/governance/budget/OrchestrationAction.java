package com.orquestrador.governance.budget;

/**
 * Os atos de orquestração cobrados NA ABERTURA no ledger (T.4; ADR-0016 rev. 2026-07-04) — o
 * anti-enxame: spawnar/acordar/abrir custa ANTES de rodar. Moeda própria (centavos de
 * orquestração), nunca somada ao teto de dólar. Os preços relativos dizem o peso do ato:
 * criar um agente pesa 10 wakeups; um wakeup pesa 10 aberturas de run.
 */
public enum OrchestrationAction {

    AGENT_SPAWN(100),
    WAKEUP(10),
    RUN_OPEN(1);

    private final long priceCents;

    OrchestrationAction(long priceCents) {
        this.priceCents = priceCents;
    }

    public long priceCents() {
        return priceCents;
    }
}
