package com.orquestrador.governance.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Aberto quando um hard limit e cruzado. Um incidente OPEN = o escopo esta pausado (o preflight
 * bloqueia novos Runs). Resolver = override aprovado (governanca), que destrava.
 */
@Entity
@Table(name = "budget_incident")
public class BudgetIncident {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "spent_cents", nullable = false)
    private long spentCents;

    @Column(name = "limit_cents", nullable = false)
    private long limitCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected BudgetIncident() {
    }

    public BudgetIncident(UUID id, UUID companyId, ScopeType scopeType, UUID scopeId, UUID policyId,
                          long spentCents, long limitCents) {
        this.id = id;
        this.companyId = companyId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.policyId = policyId;
        this.spentCents = spentCents;
        this.limitCents = limitCents;
        this.status = IncidentStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public void resolve() {
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public UUID getScopeId() {
        return scopeId;
    }

    public long getSpentCents() {
        return spentCents;
    }

    public long getLimitCents() {
        return limitCents;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
