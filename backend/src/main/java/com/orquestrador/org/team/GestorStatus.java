package com.orquestrador.org.team;

/**
 * O marcador de autoridade delegada do gestor (ADR-0046, D-ORG-2). Todo time com gestor nasce em
 * ESTAGIO; um ato explícito do dono promove pra PROMOVIDO — nunca automático, nunca rebaixa.
 * F-ORG-C lê este estado pra decidir se o parecer do gestor é consultivo ou decisório; F-ORG-D lê
 * pra decidir se o dirigir dele passa pelo gate (em lote) ou não (direto).
 */
public enum GestorStatus {
    ESTAGIO,
    PROMOVIDO
}
