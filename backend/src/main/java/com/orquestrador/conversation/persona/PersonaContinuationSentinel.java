package com.orquestrador.conversation.persona;

import com.orquestrador.org.agent.CeoPersona;

/**
 * As mensagens-SENTINELA do fluxo de personas (ADR-0031, M6) — ZERO migração, como a
 * {@code ContinuationSentinel} da ADR-0030: o turno nasce {@code conversation_turn} normal cujo
 * {@code owner_message} É a sentinela. Ambas começam com {@code [continuação} de propósito: a
 * thread do board as ESCONDE pelo MESMO filtro (D4: bastão invisível na conversa, visível na
 * trilha de runs). O que muda é quem REIVINDICA o turno — o {@code agent_id} é a persona-alvo.
 */
public final class PersonaContinuationSentinel {

    private static final String BASTAO_PREFIX = "[continuação — bastão";
    private static final String PROSSIGA_PREFIX = "[continuação — prossiga";

    private PersonaContinuationSentinel() {
    }

    /** Bastão adiante (D6): a persona-alvo lê o entregável e assume o próximo turno. */
    public static String bastao(CeoPersona origem, CeoPersona para, String bastaoPath, String resumo) {
        return BASTAO_PREFIX + " de " + origem.dbValue() + " para " + para.dbValue()
                + ": leia " + bastaoPath + (resumo == null || resumo.isBlank() ? "" : " — " + resumo) + "]";
    }

    /** A saída da trava M9: a origem prossegue com a melhor premissa e SINALIZA, em vez de travar. */
    public static String prossiga(String lacuna) {
        return PROSSIGA_PREFIX + " com a melhor premissa e SINALIZE-a no entregável; a lacuna seguiu"
                + " sem resposta: " + lacuna + "]";
    }

    /** É a sentinela "prossiga" (a saída da trava M9)? — reincidir depois dela vira corte. */
    public static boolean isProssiga(String ownerMessage) {
        return ownerMessage != null && ownerMessage.stripLeading().startsWith(PROSSIGA_PREFIX);
    }

    /** Turno interno do fluxo de personas (bastão/prossiga/kickback)? — o guard de idempotência. */
    public static boolean isPersonaFlow(String ownerMessage) {
        if (ownerMessage == null) {
            return false;
        }
        String message = ownerMessage.stripLeading();
        return message.startsWith(BASTAO_PREFIX) || message.startsWith(PROSSIGA_PREFIX)
                || KickbackTurn.isKickback(ownerMessage);
    }
}
