package com.orquestrador.governance.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Lancamento de custo — o LEDGER, append-only e a fonte de verdade. O gasto de um escopo e sempre
 * recomputado por SUM(cost_cents); contadores denormalizados (se houver) sao so display (ADR-0006).
 * Dinheiro em centavos inteiros (sem float).
 */
@Entity
@Table(name = "cost_event")
public class CostEvent {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "issue_id")
    private UUID issueId;

    @Column
    private String provider;

    @Column
    private String model;

    @Column(name = "cost_cents", nullable = false)
    private long costCents;

    @Column(name = "tokens_input", nullable = false)
    private int tokensInput;

    @Column(name = "tokens_output", nullable = false)
    private int tokensOutput;

    /**
     * O ato de orquestração que este lançamento cobra (T.4); nulo = lançamento de token/dólar.
     * Moeda SEPARADA: as somas por escopo filtram por esta coluna — dólar nunca vê orquestração.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "orchestration_action")
    private OrchestrationAction orchestrationAction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CostEvent() {
    }

    public CostEvent(UUID id, UUID companyId, UUID agentId, UUID runId, UUID issueId,
                     String provider, String model, long costCents, int tokensInput, int tokensOutput) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.runId = runId;
        this.issueId = issueId;
        this.provider = provider;
        this.model = model;
        this.costCents = costCents;
        this.tokensInput = tokensInput;
        this.tokensOutput = tokensOutput;
        this.createdAt = Instant.now();
    }

    /** Lançamento de ORQUESTRAÇÃO: cobra o ato na abertura, na conta de quem o CAUSOU (nulo = humano). */
    public static CostEvent orchestration(UUID id, UUID companyId, UUID causedByAgentId, UUID runId,
                                          OrchestrationAction action) {
        CostEvent event = new CostEvent(id, companyId, causedByAgentId, runId, null,
                null, null, action.priceCents(), 0, 0);
        event.orchestrationAction = action;
        return event;
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

    public UUID getRunId() {
        return runId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getTokensInput() {
        return tokensInput;
    }

    public int getTokensOutput() {
        return tokensOutput;
    }

    public long getCostCents() {
        return costCents;
    }

    public OrchestrationAction getOrchestrationAction() {
        return orchestrationAction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
