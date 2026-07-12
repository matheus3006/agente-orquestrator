package com.orquestrador.execution.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N2 (ADR-0038, D3): a heurística "esta linha cheira a sessão perdida" — irmã do
 * {@link CredentialFailureSignal}. Só enriquece um run que JÁ falhou COM priorSession (resume);
 * distingue "a sessão sumiu" (⟹ fresh start barulhento) de uma falha comum (⟹ handle preservado).
 */
class SessionLostSignalTest {

    @Test
    void reconheceOsSinaisDeSessaoSumidaDosDoisMotores() {
        assertThat(SessionLostSignal.detectedIn("No conversation found with session ID: abc123")).isTrue();
        assertThat(SessionLostSignal.detectedIn("error: session not found")).isTrue();
        assertThat(SessionLostSignal.detectedIn("No rollout found for the given session")).isTrue();
        assertThat(SessionLostSignal.detectedIn("could not find session xyz")).isTrue();
    }

    @Test
    void ignoraProsaNormalEOutrasFalhas() {
        assertThat(SessionLostSignal.detectedIn("Trabalhando na sua tarefa agora")).isFalse();
        // O bug de gramática do resume (R6, já corrigido pelo PR #15) NÃO é sessão perdida —
        // não pode disparar fresh start (é erro de argv, não de sessão sumida).
        assertThat(SessionLostSignal.detectedIn("error: unexpected argument '--resume' found")).isFalse();
        assertThat(SessionLostSignal.detectedIn(null)).isFalse();
        assertThat(SessionLostSignal.detectedIn("")).isFalse();
    }
}
