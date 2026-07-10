package com.orquestrador.conversation.persona;

import java.util.UUID;

/**
 * Contrato de LUGAR dos bastões-entregável das personas (ADR-0031, D4/D6): artefatos no workspace
 * de conversa COMPARTILHADO, namespaced sob {@code .orq/conversa/<conversationId>/} — irmão do
 * {@code .orq/handoff/<conversationId>.md} da ADR-0030 (a nota-de-trabalho por persona e os
 * bastões-entregável são notas DISTINTAS no mesmo chão, D8). {@code .orq/*} fica FORA de
 * {@code materializedSubtrees}: o bastão SOBREVIVE à re-materialização entre Runs e atravessa
 * as personas (todas resolvem o MESMO workspace por conversationId, M4). ZERO schema: o bastão
 * é lugar, não linha — a EXISTÊNCIA do entregável é o sinal (D6).
 */
public final class ConversationBaton {

    private ConversationBaton() {
    }

    /** O dossiê do grill (A1): APENDADO a cada turno pelo Questionador; o "sim" só CARIMBA. */
    public static String dossiePath(UUID conversationId) {
        return ".orq/conversa/" + conversationId + "/dossie.md";
    }

    /** A estratégia de ORQUESTRAÇÃO do Planejador (D12): escopo, nunca conteúdo de execução. */
    public static String estrategiaPath(UUID conversationId) {
        return ".orq/conversa/" + conversationId + "/estrategia.md";
    }
}
