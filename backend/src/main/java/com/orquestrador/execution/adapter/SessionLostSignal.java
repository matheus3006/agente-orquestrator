package com.orquestrador.execution.adapter;

import java.util.List;
import java.util.Locale;

/**
 * Heurística de "esta linha cheira a sessão nativa perdida" (F-N2/ADR-0038, D3) — irmã do
 * {@link CredentialFailureSignal}. Os CLIs avisam "não achei a sessão" em prosa quando um
 * {@code --resume}/{@code resume <id>} aponta para um handle que sumiu (retenção limpou, outro
 * container, id inválido), e o formato varia por versão — por isso termos, não igualdade.
 *
 * <p>Fail-soft por construção: o sinal só é consultado num run que JÁ falhou (exit != 0) E que
 * carregava priorSession (resume). Um falso-positivo custa um fresh start desnecessário (re-derivar
 * do workspace, barato — a verdade-fonte do executor); um falso-negativo mantém o erro genérico e o
 * handle preservado (o run seguinte tenta o mesmo handle). NUNCA silêncio: o executor emite
 * {@code RUN_SESSION_LOST} antes do fresh start.
 */
final class SessionLostSignal {

    private static final List<String> LOST_TERMS = List.of(
            "no conversation found", "session not found", "no rollout", "could not find session",
            "no session found", "unknown session", "invalid session", "resume failed");

    private SessionLostSignal() {
    }

    static boolean detectedIn(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lowered = line.toLowerCase(Locale.ROOT);
        return LOST_TERMS.stream().anyMatch(lowered::contains);
    }
}
