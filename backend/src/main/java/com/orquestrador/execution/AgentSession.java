package com.orquestrador.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * O ponteiro durável da Session do adapter (ADR-0002, generalizada na frente Teams para
 * Agent+Conversation): o handle nativo de retomada ({@code session_id} do claude / {@code thread_id}
 * do codex) capturado a cada Run de turno. FIAÇÃO PRONTA, FEATURE DESLIGADA: o v1 só ESCREVE
 * (write-only) — o {@code --resume} por-Conversation religa lendo daqui quando for a hora
 * (ADR-0018: continuidade v1 = carta + recorte do log, não recarga bruta). Status/custo/lease da
 * forma-alvo do ADR-0002 ficam de fora até existir leitor.
 */
@Entity
@Table(name = "agent_session", uniqueConstraints = @UniqueConstraint(
        name = "uq_agent_session_agent_conversation", columnNames = {"agent_id", "conversation_id"}))
public class AgentSession {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "adapter_type", nullable = false)
    private String adapterType;

    @Column(name = "external_session_id")
    private String externalSessionId;

    @Column(name = "last_run_id")
    private UUID lastRunId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentSession() {
    }

    public AgentSession(UUID id, UUID companyId, UUID agentId, UUID conversationId, String adapterType) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.adapterType = adapterType;
        this.updatedAt = Instant.now();
    }

    /** Um Run de turno concluiu: o handle mais novo VENCE (o CLI rotaciona o session-id a cada uso). */
    public void touch(String externalSessionId, UUID runId, String adapterType) {
        this.externalSessionId = externalSessionId;
        this.lastRunId = runId;
        this.adapterType = adapterType;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public String getExternalSessionId() {
        return externalSessionId;
    }

    public UUID getLastRunId() {
        return lastRunId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
