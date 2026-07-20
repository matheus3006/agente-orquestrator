package com.orquestrador.memory;

/**
 * De onde a Decisão veio (ADR-0019/D4): {@code DONO_NO_GATE} = decidida pelo board num gate
 * (approval pré-hoc / review pós-hoc — a fábrica de decisões, D5); {@code PROPOSTA_CEO} = o
 * conteúdo nasceu de proposta do CEO (aprovada pelo dono); {@code AGENTE} = registrada por um
 * agente no seu escopo.
 *
 * <p><b>Writers de {@code AGENTE}</b> (este javadoc dizia "fatia futura, nenhum writer no v1" —
 * afirmação corrigida em 2026-07-20, stale desde o PR #10): {@code CorrecaoDeEntregaRouter} (caso B,
 * ADR-0034) e {@code AbsorcaoDeIssueRouter} (ADR-0037) gravam DIRETO via
 * {@code DecisionLog.record}, sem passar pelo gate. Desde a ADR-0048 (F-ORG-C) o valor também
 * chega pela PORTA DO GATE — {@code GateDecisionFactory}, o D5 —, quando quem carimbou a
 * review foi um gestor promovido em vez do dono.
 */
public enum DecisionOrigem {
    DONO_NO_GATE,
    PROPOSTA_CEO,
    AGENTE
}
