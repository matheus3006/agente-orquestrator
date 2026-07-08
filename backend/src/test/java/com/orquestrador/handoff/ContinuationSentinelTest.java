package com.orquestrador.handoff;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuationSentinelTest {

    @Test
    void aSentinelaApontaOBilheteDaConversaEEhReconhecivel() {
        UUID conversationId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        String sentinela = ContinuationSentinel.forConversation(conversationId);

        // Instrui a LER o bilhete daquela conversa (D3) e é auto-reconhecível (D5/M10).
        assertThat(sentinela).contains(".orq/handoff/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.md");
        assertThat(ContinuationSentinel.isContinuation(sentinela)).isTrue();
    }

    @Test
    void aMensagemDoDonoNaoEhContinuacao() {
        assertThat(ContinuationSentinel.isContinuation("Monta o time de marketing")).isFalse();
        assertThat(ContinuationSentinel.isContinuation("")).isFalse();
        assertThat(ContinuationSentinel.isContinuation(null)).isFalse();
    }
}
