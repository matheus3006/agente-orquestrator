package com.orquestrador.execution;

public record RunOutcome(boolean succeeded, String error) {

    public static RunOutcome success() {
        return new RunOutcome(true, null);
    }

    public static RunOutcome failure(String error) {
        return new RunOutcome(false, error);
    }
}
