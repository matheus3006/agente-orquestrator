package com.orquestrador.planvalidation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma CHECAGEM conferida sobre o plano de um run (V33). Linha append-only, exceto por
 * {@link #ownerAudit} — o único campo que o dono edita depois, para dizer se o local acertou.
 */
@Entity
@Table(name = "plan_validation")
public class StoredPlanValidation {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "check_id", nullable = false)
    private String checkId;

    /** O texto da checagem no momento da conferência — o molde pode versionar depois. */
    @Column(name = "check_text", nullable = false)
    private String checkText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Presence presence;

    @Column
    private String evidence;

    @Column(nullable = false)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** NULL enquanto o dono não auditou. É a régua de precisão do Inviolável §4. */
    @Column(name = "owner_audit")
    private Boolean ownerAudit;

    protected StoredPlanValidation() {
    }

    public StoredPlanValidation(UUID id, UUID companyId, UUID runId, UUID agentId, UUID issueId,
                                String checkId, String checkText, Presence presence, String evidence,
                                String model, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.runId = runId;
        this.agentId = agentId;
        this.issueId = issueId;
        this.checkId = checkId;
        this.checkText = checkText;
        this.presence = presence;
        this.evidence = evidence;
        this.model = model;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getRunId() { return runId; }
    public UUID getAgentId() { return agentId; }
    public UUID getIssueId() { return issueId; }
    public String getCheckId() { return checkId; }
    public String getCheckText() { return checkText; }
    public Presence getPresence() { return presence; }
    public String getEvidence() { return evidence; }
    public String getModel() { return model; }
    public Instant getCreatedAt() { return createdAt; }
    public Boolean getOwnerAudit() { return ownerAudit; }
}
