package com.orquestrador.handoff;

import java.util.UUID;

/**
 * A mensagem-SENTINELA de um turno-continuação (ADR-0030, D3/D5). ZERO migração: o turno de
 * continuação nasce como um {@code conversation_turn} normal cujo {@code owner_message} É esta
 * sentinela. Três leitores, um só prefixo:
 * <ul>
 *   <li>{@code composePrompt} injeta a sentinela em "## Mensagem do dono" — instrui o agente a LER
 *       o bilhete e retomar (D3);</li>
 *   <li>a thread do board ESCONDE os turnos-sentinela — invisível na conversa (D5);</li>
 *   <li>a trava anti-loop CONTA os turnos-sentinela consecutivos (D7/M10).</li>
 * </ul>
 * O prefixo é NOSSO (nunca input do dono) ⟹ detectar por prefixo é seguro no v1 (D5).
 */
public final class ContinuationSentinel {

    private static final String PREFIX = "[continuação";

    private ContinuationSentinel() {
    }

    /** A {@code owner_message} de um turno-continuação: aponta o agente ao bilhete da conversa. */
    public static String forConversation(UUID conversationId) {
        return PREFIX + " — leia " + HandoffNote.relativePath(conversationId) + " e retome o plano]";
    }

    /** É um turno-continuação (sistema), não uma mensagem do dono? */
    public static boolean isContinuation(String ownerMessage) {
        return ownerMessage != null && ownerMessage.stripLeading().startsWith(PREFIX);
    }
}
