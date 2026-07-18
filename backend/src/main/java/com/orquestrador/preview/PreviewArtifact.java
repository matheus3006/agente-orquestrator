package com.orquestrador.preview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A foto IMUTAVEL do entregavel publicado por um Run (F.9/ADR-0021 + emenda R29-A): 1 arquivo
 * capturado no settle de run SUCCEEDED pelo contrato de lugar {@code .orq/preview/} — o prototipo
 * {@code index.html} (content_type=html) ou o entregavel de pesquisa {@code *.md}
 * (content_type=markdown). {@code source_filename} guarda a proveniencia (o fallback captura .md
 * de nome livre — a foto nao pode ser anonima). Nasce por-Run (auditoria: a Review embute a
 * versao exata do run revisado), apresenta por-Issue (versao ordinal; append-only, sem
 * status/update/soft-delete — padrao savelog). A unique {@code (issue_id, version)} guarda o
 * ordinal ("a review R aprovou a v3"); e segura porque os runs de uma Issue sao serializados
 * pelo execution_lock.
 */
@Entity
@Table(name = "preview_artifact", uniqueConstraints =
        @UniqueConstraint(name = "uq_preview_artifact_issue_version", columnNames = {"issue_id", "version"}))
public class PreviewArtifact {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private int version;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "source_filename", nullable = false)
    private String sourceFilename;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PreviewArtifact() {
    }

    public PreviewArtifact(UUID id, UUID companyId, UUID issueId, UUID agentId, UUID runId,
                           int version, String content, String contentHash, String contentType,
                           String sourceFilename) {
        this.id = id;
        this.companyId = companyId;
        this.issueId = issueId;
        this.agentId = agentId;
        this.runId = runId;
        this.version = version;
        this.content = content;
        this.contentHash = contentHash;
        this.contentType = contentType;
        this.sourceFilename = sourceFilename;
        this.sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getRunId() {
        return runId;
    }

    public int getVersion() {
        return version;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getContentType() {
        return contentType;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
