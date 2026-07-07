package com.orquestrador.memory;

/**
 * De onde a Decisão veio (ADR-0019/D4): {@code DONO_NO_GATE} = decidida pelo board num gate
 * (approval pré-hoc / review pós-hoc — a fábrica de decisões, D5); {@code PROPOSTA_CEO} = o
 * conteúdo nasceu de proposta do CEO (aprovada pelo dono); {@code AGENTE} = registrada por um
 * agente no seu escopo (fatia futura — nenhum writer no v1).
 */
public enum DecisionOrigem {
    DONO_NO_GATE,
    PROPOSTA_CEO,
    AGENTE
}
