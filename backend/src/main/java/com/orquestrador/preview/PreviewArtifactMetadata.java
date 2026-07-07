package com.orquestrador.preview;

import java.time.Instant;
import java.util.UUID;

/**
 * Projecao de METADADOS do preview_artifact — tudo MENOS o {@code content} (text de ate 2MB).
 * As listas (versoes da Issue, badge do Mission Control no poll de 2s) leem por aqui; o content
 * so viaja Postgres→JVM no endpoint de conteudo por id (achado da revisao adversarial F.9).
 */
public interface PreviewArtifactMetadata {

    UUID getId();

    UUID getIssueId();

    UUID getAgentId();

    UUID getRunId();

    int getVersion();

    String getContentHash();

    int getSizeBytes();

    Instant getCreatedAt();
}
