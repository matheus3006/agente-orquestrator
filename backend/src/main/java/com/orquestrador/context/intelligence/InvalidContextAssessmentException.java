package com.orquestrador.context.intelligence;

/** Saída do modelo recusada pelo contrato final do backend. */
public class InvalidContextAssessmentException extends RuntimeException {

    public InvalidContextAssessmentException(String message) {
        super(message);
    }
}
