package com.orquestrador.governance.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Um limite de gasto (hard) atado a um escopo (company ou agent). */
@Entity
@Table(name = "budget_policy")
public class BudgetPolicy {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "limit_cents", nullable = false)
    private long limitCents;

    @Column(name = "warn_cents")
    private Long warnCents;

    /** Teto de ORQUESTRAÇÃO do escopo (T.4) — moeda própria (atos), separada do dólar. Nulo = sem teto. */
    @Column(name = "orchestration_limit_cents")
    private Long orchestrationLimitCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BudgetPolicy() {
    }

    public BudgetPolicy(UUID id, UUID companyId, ScopeType scopeType, UUID scopeId, long limitCents, Long warnCents) {
        this.id = id;
        this.companyId = companyId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.limitCents = limitCents;
        this.warnCents = warnCents;
        this.createdAt = Instant.now();
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

    public long getLimitCents() {
        return limitCents;
    }

    public Long getWarnCents() {
        return warnCents;
    }

    public Long getOrchestrationLimitCents() {
        return orchestrationLimitCents;
    }

    /** Liga o teto de orquestração da policy (T.4). */
    public void limitarOrquestracao(Long orchestrationLimitCents) {
        this.orchestrationLimitCents = orchestrationLimitCents;
    }
}
