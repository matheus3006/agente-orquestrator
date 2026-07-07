package com.orquestrador.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash de chaves de API de agente. Apenas o hash (hex SHA-256, minusculo) e persistido;
 * o segredo em claro nunca toca o banco nem os logs. Fonte unica para minting e verificacao.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel", exception);
        }
    }
}
