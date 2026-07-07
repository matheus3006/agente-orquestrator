package com.orquestrador.memory;

/**
 * Os tiers DURÁVEIS da memória da empresa que moram em {@code company_memory} (ADR-0019/D3).
 * v1 só materializa a CARTA (sempre-on, derivada). Os demais tiers de D3 não vivem aqui:
 * o episódico é o {@code activity_log}; o Ledger por-Issue é read-model do run concluinte;
 * ADR-da-Empresa/Playbook (graduação por padrão, D12) entram quando houver consumidor.
 */
public enum MemoryKind {
    CARTA
}
