package com.orquestrador.preview.server;

import java.time.Instant;
import java.util.UUID;

/**
 * Projecao de METADADOS do preview_server_thumbnail — tudo MENOS o {@code content} (o PNG bytea de
 * ate 2MB). As listas (versoes por Issue) leem por aqui; os bytes so viajam Postgres->JVM no
 * endpoint de conteudo por id (molde do {@code PreviewArtifactMetadata}).
 */
public interface PreviewServerThumbnailMetadata {

    UUID getId();

    UUID getIssueId();

    UUID getAgentId();

    UUID getRunId();

    String getContentHash();

    int getSizeBytes();

    Instant getCreatedAt();
}
