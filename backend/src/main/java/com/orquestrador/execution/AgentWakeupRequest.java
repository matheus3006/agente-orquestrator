package com.orquestrador.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Sinal de "acorde o Agent". Multiplos wakeups do mesmo Agent coalescem num Run
 * (ver {@link WakeupStatus}); nada se perde.
 */
@Entity
@Table(name = "agent_wakeup_request")
public class AgentWakeupRequest {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "issue_id")
    private UUID issueId;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WakeupStatus status = WakeupStatus.PENDING;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** A cadeia de quem CAUSOU este wakeup (T.4); vazio = ato humano direto (operador/dono). */
    @Column(name = "spawn_lineage", nullable = false)
    private String spawnLineage = "";

    protected AgentWakeupRequest() {
    }

    public AgentWakeupRequest(UUID id, UUID companyId, UUID agentId, UUID issueId, String reason,
                              com.orquestrador.execution.lineage.SpawnLineage spawnLineage) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.issueId = issueId;
        this.reason = reason;
        this.status = WakeupStatus.PENDING;
        this.createdAt = Instant.now();
        this.spawnLineage = spawnLineage.encoded();
    }

    /** Liga este wakeup a um Run com o status de coalescing apropriado. */
    public void attach(UUID runId, WakeupStatus status) {
        this.runId = runId;
        this.status = status;
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

    public UUID getIssueId() {
        return issueId;
    }

    public String getReason() {
        return reason;
    }

    public WakeupStatus getStatus() {
        return status;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getSpawnLineage() {
        return spawnLineage;
    }
}
