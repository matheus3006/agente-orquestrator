package com.orquestrador.handoff;

import java.util.UUID;

/**
 * Contrato de LUGAR do bilhete de handoff (ADR-0030): um artefato no Workspace {@code _agent},
 * namespaced sob {@code .orq/handoff/<conversationId>.md}, escrito pelo próprio agente que viveu
 * o turno. Espelha o {@code .orq/preview/index.html} do preview — {@code .orq/*} fica FORA de
 * {@code materializedSubtrees} (M6), então o bilhete SOBREVIVE à re-materialização entre Runs.
 * ZERO schema: o bilhete é lugar, não linha (D3).
 */
public final class HandoffNote {

    /** Teto de leitura do bilhete (texto destilado; folgado, não é blob de preview). */
    public static final long MAX_BYTES = 256L * 1024L;

    private HandoffNote() {
    }

    /** O bilhete é por-CONVERSA (o handoff é conversation-scoped — ADR-0030, paralela à Fatia 2). */
    public static String relativePath(UUID conversationId) {
        return ".orq/handoff/" + conversationId + ".md";
    }
}
