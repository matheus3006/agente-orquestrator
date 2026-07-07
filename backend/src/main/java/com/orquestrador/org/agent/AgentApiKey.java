package com.orquestrador.org.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Chave de API de um agente. Apenas o hash SHA-256 do segredo e persistido ({@code keyHash});
 * o segredo em claro nunca toca o banco nem os logs.
 */
@Entity
@Table(name = "agent_api_key")
public class AgentApiKey {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AgentApiKey() {
    }

    public AgentApiKey(UUID id, UUID companyId, UUID agentId, String keyHash) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.keyHash = keyHash;
        this.createdAt = Instant.now();
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

    public String getKeyHash() {
        return keyHash;
    }
}
