package com.orquestrador.context;

/** Falha canônica legível: diferente de falha auxiliar de inteligência, ela falha o Run. */
public class ContextCompilationException extends RuntimeException {

    public ContextCompilationException(String message) {
        super(message);
    }

    public ContextCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
