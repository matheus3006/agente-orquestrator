package com.orquestrador.work.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Unidade de trabalho. Single-assignee + execution lock: o {@code executionLockRunId}
 * e a trava que garante que so um Run executa a Issue por vez. A transicao de posse
 * acontece via UPDATE guardado atomico (ver {@link IssueRepository#tryCheckout}),
 * nunca por read-modify-write em memoria.
 */
@Entity
@Table(name = "issue")
public class Issue {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueStatus status = IssueStatus.OPEN;

    @Column(name = "assignee_agent_id")
    private UUID assigneeAgentId;

    @Column(name = "execution_lock_run_id")
    private UUID executionLockRunId;

    // Escala 1-5 do review gate (ADR-0012): 1 = sem risco; risco >= limiar → PENDING_REVIEW no settle.
    @Column(name = "risk_level", nullable = false)
    private int riskLevel = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Issue() {
    }

    public Issue(UUID id, UUID companyId, String title, String description, int riskLevel) {
        this.id = id;
        this.companyId = companyId;
        this.title = title;
        this.description = description;
        this.status = IssueStatus.OPEN;
        this.riskLevel = riskLevel;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public UUID getAssigneeAgentId() {
        return assigneeAgentId;
    }

    public UUID getExecutionLockRunId() {
        return executionLockRunId;
    }

    public int getRiskLevel() {
        return riskLevel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
