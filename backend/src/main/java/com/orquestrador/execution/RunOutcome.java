package com.orquestrador.execution;

/**
 * {@code noop} (R22-A, ADR-0011 emendada): o run terminou SEM FAZER NADA — o adapter sinalizou
 * que nunca invocou o motor (ex.: arquétipo process sem comando). Não é sucesso: um no-op sobre
 * uma Issue estaciona em BLOCKED no settle ("run que não fez nada nunca fecha issue" — invariante
 * do dono, 2026-07-16), com o porquê legível no {@code error}.
 */
public record RunOutcome(boolean succeeded, String error, boolean noop) {

    public static RunOutcome success() {
        return new RunOutcome(true, null, false);
    }

    public static RunOutcome failure(String error) {
        return new RunOutcome(false, error, false);
    }

    public static RunOutcome noop(String detail) {
        return new RunOutcome(false, detail, true);
    }
}
