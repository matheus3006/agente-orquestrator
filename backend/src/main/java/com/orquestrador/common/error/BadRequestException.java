package com.orquestrador.common.error;

/** Contrato do request violado além do que o Bean Validation declara (ex.: campo condicional). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
