package com.orquestrador.execution.adapter;

import java.util.List;
import java.util.Locale;

/**
 * Heurística de "esta linha cheira a falha de credencial do plano" (peça 4 do ADR-0016). Os CLIs
 * avisam auth quebrada em prosa (fora da gramática JSON do stream) e formatos variam por versão —
 * por isso termos, não igualdade. Fail-soft por construção: o sinal só ENRIQUECE o erro de um run
 * que JÁ falhou (exit != 0); um falso-positivo jamais derruba run saudável, e um falso-negativo
 * mantém o erro genérico com exit code — nunca silêncio.
 */
final class CredentialFailureSignal {

    private static final List<String> AUTH_TERMS = List.of(
            "login", "logged", "log in", "unauthorized", "401", "authentication",
            "credential", "api key", "oauth", "expired");

    private CredentialFailureSignal() {
    }

    static boolean detectedIn(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lowered = line.toLowerCase(Locale.ROOT);
        return AUTH_TERMS.stream().anyMatch(lowered::contains);
    }
}
