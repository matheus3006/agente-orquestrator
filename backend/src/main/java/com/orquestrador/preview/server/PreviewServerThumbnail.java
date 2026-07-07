package com.orquestrador.preview.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * O PNG DURAVEL do app v2 (F.13/ADR-0027): o 5º container da familia (o shot com Chromium)
 * fotografou o servidor de preview ENQUANTO ele estava vivo (janela pre-GC) e guardou os bytes
 * aqui. Nasce por-run (espelha o {@code preview_server_manifest} V21, tambem por-run), append-only,
 * e SOBREVIVE ao GC do workspace (ADR-0026): quando o {@code WorkspaceReaper} coleta o workspace e
 * o "Rodar app" morre, esta linha e o registro visual que fica — o irmao binario da foto v1
 * ({@code preview_artifact}, TEXT/HTML por-Issue). O vigente para exibir e o ULTIMO da Issue.
 *
 * <p>{@code content} e {@code bytea} no Postgres (V22); o {@code length} do @Column so molda o
 * schema H2 dos testes (Flyway off la) — o Postgres real usa o tipo da migration. Sem {@code @Lob}
 * (que viraria large-object OID no Postgres): {@code byte[]} mapeia direto para {@code bytea}.
 */
@Entity
@Table(name = "preview_server_thumbnail")
public class PreviewServerThumbnail {

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

    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PreviewServerThumbnail() {
    }

    public PreviewServerThumbnail(UUID id, UUID companyId, UUID issueId, UUID agentId, UUID runId,
                                  byte[] content, String contentHash) {
        this.id = id;
        this.companyId = companyId;
        this.issueId = issueId;
        this.agentId = agentId;
        this.runId = runId;
        this.content = content;
        this.contentHash = contentHash;
        this.sizeBytes = content.length;
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

    public byte[] getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
