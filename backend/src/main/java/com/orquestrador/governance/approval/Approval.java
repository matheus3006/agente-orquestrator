package com.orquestrador.governance.approval;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.memory.RacionalTipo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A Approval PRÉ-HOC (ADR-0018): o CEO propõe uma AÇÃO pelo canal de saída; o board decide;
 * APPROVE dispara a ação real com ator=aprovador. ESPELHA a {@code review} (ADR-0012, o "2º
 * consumidor" que ela esperava) — request→decision, linha mutável, motivo/racional NA TABELA e
 * nunca no activity_log (ADR-0009). NÃO generaliza o {@code BudgetIncident}.
 *
 * <p>Racional por grill (ADR-0020): {@code racional_tipo} é DERIVADO do artefato no PROPOSE (D9);
 * o commit da decisão exige racional presente (D6) — LOGICO bloqueia até razão substantiva,
 * INTUICAO commita na hora (D8); {@code grill_rounds} conta as insistências do escriba (a taxa
 * que cai quando a empresa fica esperta, D12) e {@code racional_arrancado} = veio de grill.
 *
 * <p>Invariante anti-spam "no máximo 1 PENDING idêntica" = índice parcial {@code uq_approval_pending}
 * (V17, {@code where status='PENDING'} sobre company+agente+ação+fingerprint) — não declarável em
 * JPA nem forçado no H2 (mesmo desvio consciente da review); o service deduplica antes.
 */
@Entity
@Table(name = "approval")
public class Approval {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "requested_by_agent_id", nullable = false)
    private UUID requestedByAgentId;

    /** A âncora da thread (mockup §4): a proposta nasceu num turno de conversa (nullable — futuro: outras fontes). */
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ApprovalActionType actionType;

    /** Os ARGUMENTOS da ação (jsonb canônico, campos mínimos/IDs — a prosa fica na thread). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_detail", nullable = false)
    private String targetDetail;

    @Column(name = "target_fingerprint", nullable = false)
    private String targetFingerprint;

    @Column(nullable = false, columnDefinition = "text")
    private String area;

    @Column(columnDefinition = "text")
    private String resumo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by_actor_type")
    private ActorType decidedByActorType;

    @Column(name = "decided_by_actor_id")
    private UUID decidedByActorId;

    /** Feedback livre do board (ex.: o que mudar num REJECT) — distinto do racional (o PORQUÊ). */
    @Column(name = "decision_reason", columnDefinition = "text")
    private String decisionReason;

    @Column(columnDefinition = "text")
    private String racional;

    @Enumerated(EnumType.STRING)
    @Column(name = "racional_tipo", nullable = false)
    private RacionalTipo racionalTipo;

    @Column(name = "racional_arrancado", nullable = false)
    private boolean racionalArrancado;

    @Column(name = "grill_rounds", nullable = false)
    private int grillRounds;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected Approval() {
    }

    public Approval(UUID id, UUID companyId, UUID requestedByAgentId, UUID conversationId, UUID runId,
                    ApprovalActionType actionType, String targetDetail, String targetFingerprint,
                    String area, String resumo, RacionalTipo racionalTipo) {
        this.id = id;
        this.companyId = companyId;
        this.requestedByAgentId = requestedByAgentId;
        this.conversationId = conversationId;
        this.runId = runId;
        this.actionType = actionType;
        this.targetDetail = targetDetail;
        this.targetFingerprint = targetFingerprint;
        this.area = area;
        this.resumo = resumo;
        this.racionalTipo = racionalTipo;
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    /** O escriba insistiu e o racional não veio (ADR-0020/D6): conta a rodada, segue PENDING. */
    public void registerGrillRound() {
        this.grillRounds++;
    }

    /** Commit da decisão: o racional presente sela; arrancado = precisou de grill antes (D12). */
    public void decide(ApprovalDecision decision, ActorType actorType, UUID actorId,
                       String racional, String decisionReason) {
        this.status = decision == ApprovalDecision.APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        this.decidedByActorType = actorType;
        this.decidedByActorId = actorId;
        this.racional = racional;
        this.racionalArrancado = grillRounds > 0;
        this.decisionReason = decisionReason;
        this.decidedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getRequestedByAgentId() {
        return requestedByAgentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getRunId() {
        return runId;
    }

    public ApprovalActionType getActionType() {
        return actionType;
    }

    public String getTargetDetail() {
        return targetDetail;
    }

    public String getTargetFingerprint() {
        return targetFingerprint;
    }

    public String getArea() {
        return area;
    }

    public String getResumo() {
        return resumo;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public ActorType getDecidedByActorType() {
        return decidedByActorType;
    }

    public UUID getDecidedByActorId() {
        return decidedByActorId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public String getRacional() {
        return racional;
    }

    public RacionalTipo getRacionalTipo() {
        return racionalTipo;
    }

    public boolean isRacionalArrancado() {
        return racionalArrancado;
    }

    public int getGrillRounds() {
        return grillRounds;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
