package com.orquestrador.conversation.persona;

import com.orquestrador.org.agent.CeoPersona;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * As mensagens-SENTINELA do fluxo de personas (ADR-0031, M6) — ZERO migração, como a
 * {@code ContinuationSentinel} da ADR-0030: o turno nasce {@code conversation_turn} normal cujo
 * {@code owner_message} É a sentinela. Todas começam com {@code [continuação} de propósito: a
 * thread do board as ESCONDE pelo MESMO filtro (D4: bastão invisível na conversa, visível na
 * trilha de runs). O que muda é quem REIVINDICA o turno — o {@code agent_id} é a persona-alvo.
 */
public final class PersonaContinuationSentinel {

    private static final String BASTAO_PREFIX = "[continuação — bastão";
    private static final String PROSSIGA_PREFIX = "[continuação — prossiga";
    private static final String RETRABALHO_PREFIX = "[continuação — retrabalho";

    /** Espelha o {@code bastao()}: extrai só a origem ("de X para Y"), nunca confia num bloco. */
    private static final Pattern BASTAO_ORIGEM = Pattern.compile("^\\[continuação — bastão de (\\w+) para \\w+");

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

    /**
     * Auto-kickback entre personas (rodada 9): a persona-{@code origem} (quem está respondendo
     * agora) topou um defeito no entregável recebido e devolve o bastão à persona-{@code destino}
     * (quem entregou) — INVISÍVEL (D4), sem passar pelo dono (diferente do {@code needs-grilling},
     * que é VISÍVEL porque o dono precisa responder). Mesma convenção "de X para Y" do bastão.
     */
    public static String retrabalho(CeoPersona origem, CeoPersona destino, String lacuna) {
        return RETRABALHO_PREFIX + " de " + origem.dbValue() + " para " + destino.dbValue()
                + ": " + lacuna + "]";
    }

    /** É a sentinela de retrabalho (auto-kickback entre personas)? — conta na régua M9. */
    public static boolean isRetrabalho(String ownerMessage) {
        return ownerMessage != null && ownerMessage.stripLeading().startsWith(RETRABALHO_PREFIX);
    }

    /**
     * A origem do BASTÃO que este turno entrega (nunca um campo de bloco — deriva do sentinela
     * que o backend mesmo escreveu): {@code empty} se o turno não é uma continuação de bastão
     * (ex.: prossiga, retrabalho, ou a mensagem raiz do dono) — o retrabalho não tem para onde ir.
     */
    public static Optional<CeoPersona> origemDoBastao(String ownerMessage) {
        if (ownerMessage == null) {
            return Optional.empty();
        }
        Matcher matcher = BASTAO_ORIGEM.matcher(ownerMessage.stripLeading());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return CeoPersona.fromDbValue(matcher.group(1));
    }

    /** Turno interno do fluxo de personas (bastão/prossiga/kickback/retrabalho)? — guard de idempotência. */
    public static boolean isPersonaFlow(String ownerMessage) {
        if (ownerMessage == null) {
            return false;
        }
        String message = ownerMessage.stripLeading();
        return message.startsWith(BASTAO_PREFIX) || message.startsWith(PROSSIGA_PREFIX)
                || message.startsWith(RETRABALHO_PREFIX) || KickbackTurn.isKickback(ownerMessage);
    }
}
