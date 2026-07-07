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
 * Um Run de heartbeat: uma execucao de um Agent. Carrega o lease (lease_owner/expires_at)
 * que permite recovery multi-no — a chave que o heartbeat_runs do Paperclip nao tinha (ADR-0005).
 */
@Entity
@Table(name = "heartbeat_run")
public class HeartbeatRun {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "issue_id")
    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.QUEUED;

    @Column(nullable = false)
    private int attempt = 0;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "needs_followup", nullable = false)
    private boolean needsFollowup = false;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** A cadeia de quem CAUSOU este Run (T.4); vazio = ato humano direto. Nasce e não muda. */
    @Column(name = "spawn_lineage", nullable = false)
    private String spawnLineage = "";

    protected HeartbeatRun() {
    }

    public HeartbeatRun(UUID id, UUID companyId, UUID agentId, UUID issueId, RunStatus status) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.issueId = issueId;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public HeartbeatRun(UUID id, UUID companyId, UUID agentId, UUID issueId, RunStatus status,
                        com.orquestrador.execution.lineage.SpawnLineage spawnLineage) {
        this(id, companyId, agentId, issueId, status);
        this.spawnLineage = spawnLineage.encoded();
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

    public void setIssueId(UUID issueId) {
        this.issueId = issueId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public boolean isNeedsFollowup() {
        return needsFollowup;
    }

    public void setNeedsFollowup(boolean needsFollowup) {
        this.needsFollowup = needsFollowup;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getSpawnLineage() {
        return spawnLineage;
    }
}
