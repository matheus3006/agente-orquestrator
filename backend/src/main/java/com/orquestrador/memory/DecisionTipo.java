package com.orquestrador.memory;

/**
 * O tipo da Decisão (ADR-0019/D4). {@code DIRECAO} é o que a carta projeta (o consolidador só lê
 * Decisões-de-direção aprovadas pelo dono); OPERACIONAL/TECNICA ficam no Log de Decisões e nos
 * tiers de baixo.
 */
public enum DecisionTipo {
    DIRECAO,
    OPERACIONAL,
    TECNICA
}
