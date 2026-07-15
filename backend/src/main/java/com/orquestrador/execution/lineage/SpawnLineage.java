package com.orquestrador.execution.lineage;

import java.util.List;
import java.util.UUID;

/**
 * A cadeia de quem CAUSOU este trabalho (T.4; ADR-0016 rev. 2026-07-04) — ordenada da raiz ao
 * causador imediato. Raiz (cadeia vazia) = ato humano direto (operador/dono); cada agente que
 * spawna trabalho entra na cadeia via {@link #descendTo}. Imutável.
 *
 * <p>O formato persistido é privado deste módulo: UUIDs minúsculos unidos por {@code >}. Os elos
 * são tratados como texto opaco — dado estranho na coluna não tem modo de falha de parse, só não
 * casa com nenhum agente.
 */
public final class SpawnLineage {

    private static final String SEPARATOR = ">";
    private static final SpawnLineage ROOT = new SpawnLineage(List.of());

    private final List<String> causerChain;

    private SpawnLineage(List<String> causerChain) {
        this.causerChain = causerChain;
    }

    /** Cadeia vazia: trabalho causado por humano direto (operador/dono), nunca por agente. */
    public static SpawnLineage root() {
        return ROOT;
    }

    /** Reidrata da coluna; nulo/vazio = raiz (o default do schema e de linhas pré-V19). */
    public static SpawnLineage decode(String column) {
        if (column == null || column.isBlank()) {
            return ROOT;
        }
        return new SpawnLineage(List.of(column.strip().split(SEPARATOR)));
    }

    /** O que as entidades persistem na coluna {@code spawn_lineage}. */
    public String encoded() {
        return String.join(SEPARATOR, causerChain);
    }

    /** A cadeia do trabalho spawnado POR {@code causerAgentId}: esta cadeia + ele no fim. */
    public SpawnLineage descendTo(UUID causerAgentId) {
        List<String> descended = new java.util.ArrayList<>(causerChain);
        descended.add(causerAgentId.toString().toLowerCase());
        return new SpawnLineage(List.copyOf(descended));
    }

    /** O agente é ancestral desta cadeia? (a pergunta do guard anti-ciclo) */
    public boolean contains(UUID agentId) {
        return causerChain.contains(agentId.toString().toLowerCase());
    }

    /** O causador imediato (último elo); nulo = raiz — é o dono da conta no ledger de orquestração. */
    public UUID causer() {
        if (causerChain.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(causerChain.get(causerChain.size() - 1));
        } catch (IllegalArgumentException eloOpaco) {
            return null; // elo que não é UUID: cadeia segue válida p/ contains, sem conta atribuível
        }
    }

    public int depth() {
        return causerChain.size();
    }

    /** Ato humano direto (cadeia vazia)? A mao do operador/dono — nunca barrada pelo circuit-breaker. */
    public boolean isRoot() {
        return causerChain.isEmpty();
    }
}
