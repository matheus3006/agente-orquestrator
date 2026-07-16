package com.orquestrador.handoff;

import java.util.UUID;

/**
 * Veto ao loop de continuação de contexto (ADR-0030): quando a conversa está PARADA esperando o
 * dono (park-for-human, ADR-0011), reabrir a sessão só re-bate a mesma lacuna — o loop
 * kickback↔continuação que nenhuma das duas travas isoladas enxerga (cada uma conta só o próprio
 * tipo de turno; a alternância zera as duas). O {@link ContinuationTurnPoster} consulta os guards
 * ANTES de enfileirar uma continuação; qualquer veto PARA o resume — a pergunta ao dono já está
 * visível na thread e uma fala real dele destrava.
 *
 * <p>Inversão de dependência: a interface mora aqui (handoff), a regra de persona a implementa
 * ({@code conversation.persona → handoff}, a direção que JÁ existe via {@code ContinuationSentinel})
 * — sem ciclo de pacote.
 */
public interface ConversationContinuationGuard {

    /** A conversa está travada esperando o dono? (⟹ não retomar sozinho). */
    boolean parkedForHuman(UUID companyId, UUID conversationId);
}
