package com.orquestrador.common.security;

import java.util.UUID;

/**
 * O ator autenticado da requisicao atual.
 *
 * @param id        identidade do ator (userId para BOARD, agentId para AGENT)
 * @param type      BOARD (operador humano) ou AGENT
 * @param companyId company de origem do AGENT; null para BOARD (que pode pertencer a varias companies)
 */
public record Actor(UUID id, ActorType type, UUID companyId) {

    public static Actor board(UUID userId) {
        return new Actor(userId, ActorType.BOARD, null);
    }

    public static Actor agent(UUID agentId, UUID companyId) {
        return new Actor(agentId, ActorType.AGENT, companyId);
    }
}
