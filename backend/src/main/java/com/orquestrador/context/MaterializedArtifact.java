package com.orquestrador.context;

/** Um arquivo efetivamente materializado, descrito sem carregar seu corpo para o manifesto. */
public record MaterializedArtifact(String relativePath, String sha256, long bytes) {
}
