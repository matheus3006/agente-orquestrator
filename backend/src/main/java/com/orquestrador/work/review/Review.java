package com.orquestrador.work.review;

import com.orquestrador.common.security.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Um ciclo de revisão do review gate (ADR-0012): request→decision. Nasce PENDING quando o
 * {@code settleAfterRun} estaciona uma Issue de alto risco em PENDING_REVIEW, apontando o run
 * concluído cujo output está sob revisão; o board decide (APPROVE/REJECT, R.3) preenchendo
 * reviewer/decided_at/decision_reason. Linha MUTÁVEL (diferente do activity_log, que é a trilha
 * imutável); o {@code decision_reason} (texto livre) vive AQUI, nunca no activity_log (ADR-0009).
 *
 * <p>Invariante "no máximo 1 review PENDING por Issue" = índice parcial {@code uq_review_pending}
 * (V11, {@code where status='PENDING'}) — NÃO declarável em JPA e não forçado pelo H2 dos testes;
 * guard vivo só no Postgres (desvio consciente da regra "declarar a unique na entidade", que não
 * cobre índice parcial).
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_actor_type")
    private ActorType reviewerActorType;

    @Column(name = "reviewer_actor_id")
    private UUID reviewerActorId;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "parecer")
    private String parecer;

    @Column(name = "parecer_por_agent_id")
    private UUID parecerPorAgentId;

    @Column(name = "parecer_em")
    private Instant parecerEm;

    protected Review() {
    }

    /** Decide o ciclo (R.3): APPROVED sela o reviewer; o motivo (opcional) fica AQUI, nunca no log. */
    public void approve(ActorType reviewerActorType, UUID reviewerActorId, String decisionReason) {
        decide(ReviewStatus.APPROVED, reviewerActorType, reviewerActorId, decisionReason);
    }

    /** Decide o ciclo (R.3): REJECTED — o motivo orienta a edição da Issue antes do REOPEN (§7). */
    public void reject(ActorType reviewerActorType, UUID reviewerActorId, String decisionReason) {
        decide(ReviewStatus.REJECTED, reviewerActorType, reviewerActorId, decisionReason);
    }

    /**
     * O ciclo já tem parecer? LEITURA apenas — a cerca do primeiro-vence mora no UPDATE guardado
     * {@code ReviewRepository.tryRegistrarParecer} ({@code and parecer is null}), não aqui.
     *
     * <p>Não existe setter de parecer nesta entidade DE PROPÓSITO (hardening do PR #50, achado
     * 🔴 2): o que havia era um {@code registrarParecer(...)} escrevendo os 3 campos direto, e o
     * service checava {@link #temParecer()} ANTES de chamá-lo — read-then-write puro, lost update
     * sob concorrência real. Com a escrita só pelo UPDATE guardado, o caminho inseguro não existe
     * mais para ser reintroduzido por engano.
     */
    public boolean temParecer() {
        return parecer != null;
    }

    private void decide(ReviewStatus decision, ActorType actorType, UUID actorId, String reason) {
        this.status = decision;
        this.reviewerActorType = actorType;
        this.reviewerActorId = actorId;
        this.decisionReason = reason;
        this.decidedAt = Instant.now();
    }

    public Review(UUID id, UUID companyId, UUID issueId, UUID runId) {
        this.id = id;
        this.companyId = companyId;
        this.issueId = issueId;
        this.runId = runId;
        this.status = ReviewStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getRunId() {
        return runId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public ActorType getReviewerActorType() {
        return reviewerActorType;
    }

    public UUID getReviewerActorId() {
        return reviewerActorId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getParecer() {
        return parecer;
    }

    public UUID getParecerPorAgentId() {
        return parecerPorAgentId;
    }

    public Instant getParecerEm() {
        return parecerEm;
    }
}
