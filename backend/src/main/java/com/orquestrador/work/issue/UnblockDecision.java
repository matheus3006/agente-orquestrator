package com.orquestrador.work.issue;

/**
 * Decisão do board ao destravar uma Issue {@code BLOCKED} (recovery, ADR-0011): reabrir para nova
 * tentativa (mantendo o agente) ou cancelar de vez. Sem auto-retry no v1 — a re-execução do REOPEN
 * segue pelo caminho normal de wakeup.
 */
public enum UnblockDecision {
    REOPEN,
    CANCEL
}
