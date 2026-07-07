package com.orquestrador.skills;

import java.util.List;

/**
 * Resultado verificavel de um import: quantas skills foram criadas, atualizadas (nova versao),
 * inalteradas (idempotencia) e quais foram puladas (com o motivo). {@code total} = as tres primeiras.
 */
public record IndexResult(int created, int updated, int unchanged, List<String> skipped) {

    public int total() {
        return created + updated + unchanged;
    }
}
