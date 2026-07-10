package com.orquestrador.conversation.persona;

import com.orquestrador.org.agent.CeoPersona;

/**
 * O turno de KICKBACK cirúrgico (ADR-0031, D7): diferente do bastão, ele é VISÍVEL na thread —
 * precedente do aviso de corte da ADR-0030 ("não é sentinela, aparece normalmente") — porque a
 * resposta do Questionador a ele é a re-grelha que o DONO precisa ler e responder. O
 * {@code owner_message} carrega a ORIGEM (a persona que travou) e a LACUNA: é assim que o
 * Questionador, num run fresco (sem {@code --resume}), sabe a quem devolver o bastão.
 */
public final class KickbackTurn {

    static final String PREFIX = "Re-grelha — ";

    private KickbackTurn() {
    }

    public static String ownerMessage(CeoPersona origem, String lacuna) {
        return PREFIX + "o " + origem.dbValue() + " travou numa lacuna que só o dono responde: "
                + lacuna + " (depois de grelhar, devolva o bastão a `" + origem.dbValue() + "`)";
    }

    /** É um turno de kickback? (prefixo NOSSO; o dono digitá-lo só inflaria a trava M9 — inócuo) */
    public static boolean isKickback(String ownerMessage) {
        return ownerMessage != null && ownerMessage.stripLeading().startsWith(PREFIX);
    }
}
