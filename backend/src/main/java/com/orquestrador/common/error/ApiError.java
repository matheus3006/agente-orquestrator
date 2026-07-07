package com.orquestrador.common.error;

/**
 * Corpo de erro consistente para toda a API: {@code {error, message}}.
 */
public record ApiError(String error, String message) {
}
