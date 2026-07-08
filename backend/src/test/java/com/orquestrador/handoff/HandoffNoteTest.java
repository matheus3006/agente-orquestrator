package com.orquestrador.handoff;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffNoteTest {

    @Test
    void bilheteEhPorConversaSobONamespaceOrq() {
        UUID conversationId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        // O contrato de lugar (D3): namespace .orq/ (fora de materializedSubtrees, M6) + por-conversa.
        assertThat(HandoffNote.relativePath(conversationId))
                .isEqualTo(".orq/handoff/11111111-2222-3333-4444-555555555555.md");
    }
}
