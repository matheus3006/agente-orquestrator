package com.orquestrador.work.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Unidade de trabalho. Single-assignee + execution lock: o {@code executionLockRunId}
 * e a trava que garante que so um Run executa a Issue por vez. A transicao de posse
 * acontece via UPDATE guardado atomico (ver {@link IssueRepository#tryCheckout}),
 * nunca por read-modify-write em memoria.
 */
@Entity
@Table(name = "issue", uniqueConstraints =
        @UniqueConstraint(name = "uq_issue_company_seq", columnNames = {"company_id", "seq"}))
public class Issue {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    // Identidade sequencial humana (#N por company, ADR-0033): denso, imutavel, comeca em 1.
    // Alocado em IssueService.create sob o lock da linha da company; a unique e backstop.
    @Column(nullable = false)
    private int seq;

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

    // Caso B (ADR-0034, V26): a task que esta corrige (self-FK, 1:1). Vazia em task normal;
    // preenchida so na corretiva — a linhagem (correcao de correcao) e a corrente ate a raiz.
    @Column(name = "corrige_issue_id")
    private UUID corrigeIssueId;

    // Escala 1-5 do review gate (ADR-0012): 1 = sem risco; risco >= limiar → PENDING_REVIEW no settle.
    @Column(name = "risk_level", nullable = false)
    private int riskLevel = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Issue() {
    }

    public Issue(UUID id, UUID companyId, int seq, String title, String description, int riskLevel) {
        this.id = id;
        this.companyId = companyId;
        this.seq = seq;
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

    public int getSeq() {
        return seq;
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

    public UUID getCorrigeIssueId() {
        return corrigeIssueId;
    }

    /**
     * O nascimento da CORRETIVA (caso B, ADR-0034): ligada a task que corrige e JA COM DONO —
     * o assignee pre-setado entrega a task na mesa do arquiteto; o guard do
     * {@link IssueRepository#tryCheckout} ({@code assignee is null or = :agentId}) garante que
     * so ele a executa. So faz sentido antes do primeiro save (e o caminho de nascer, nao um update).
     */
    void nascerCorrigindo(UUID corrigeIssueId, UUID assigneeAgentId) {
        this.corrigeIssueId = corrigeIssueId;
        this.assigneeAgentId = assigneeAgentId;
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
