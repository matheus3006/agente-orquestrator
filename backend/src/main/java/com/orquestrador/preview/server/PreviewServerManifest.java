package com.orquestrador.preview.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A DECLARACAO de servidor de um Run (F.10/ADR-0023): o agente anunciou, escrevendo o contrato
 * de lugar {@code .orq/preview/server.json}, que o app do workspace sobe com {@code command} e
 * escuta em {@code port}. Registrada no settle e append-only por run — sem dedup, sem status,
 * sem update: o vigente de uma Issue e o ULTIMO (runs serializados pelo execution_lock). Subir o
 * servidor usa o comando PERSISTIDO (o que se declara e o que executa); o runtime (vivo/porta do
 * host) NUNCA mora aqui — mora no Docker por label (tabela de vivos = estado especular, rejeitada).
 */
@Entity
@Table(name = "preview_server_manifest")
public class PreviewServerManifest {

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

    @Column(columnDefinition = "text", nullable = false)
    private String command;

    @Column(nullable = false)
    private int port;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PreviewServerManifest() {
    }

    public PreviewServerManifest(UUID id, UUID companyId, UUID issueId, UUID agentId, UUID runId,
                                 String command, int port) {
        this.id = id;
        this.companyId = companyId;
        this.issueId = issueId;
        this.agentId = agentId;
        this.runId = runId;
        this.command = command;
        this.port = port;
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

    public String getCommand() {
        return command;
    }

    public int getPort() {
        return port;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
