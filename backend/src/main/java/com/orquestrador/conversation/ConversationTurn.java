package com.orquestrador.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Um TURNO da conversa (ADR-0018/A.8): a mensagem do dono + a resposta do CEO — 1 turno = 1 Run.
 * Nasce {@code PENDING} endereçado ao CEO ({@code agent_id}); o motor faz o CLAIM (preenche
 * {@code run_id}, guardado — ver {@code ConversationTurnCoordinator}) e o settle grava
 * {@code ceo_reply} (ANSWERED) ou marca FAILED. A thread mostra SÓ o que está aqui (disclosure:
 * o StepTrace do Run fica a 1 clique pelo {@code run_id}, nunca despejado na conversa).
 */
@Entity
@Table(name = "conversation_turn")
public class ConversationTurn {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** O CEO endereçado no momento do post (resolvido por role='ceo'; auditável se o CEO trocar). */
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "owner_message", nullable = false, columnDefinition = "text")
    private String ownerMessage;

    @Column(name = "ceo_reply", columnDefinition = "text")
    private String ceoReply;

    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TurnStatus status = TurnStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected ConversationTurn() {
    }

    public ConversationTurn(UUID id, UUID conversationId, UUID companyId, UUID agentId, String ownerMessage) {
        this.id = id;
        this.conversationId = conversationId;
        this.companyId = companyId;
        this.agentId = agentId;
        this.ownerMessage = ownerMessage;
        this.status = TurnStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** Settle de sucesso: a resposta que o CEO decidiu contar vira a thread (disclosure). */
    public void answer(String reply) {
        this.ceoReply = reply;
        this.status = TurnStatus.ANSWERED;
        this.answeredAt = Instant.now();
    }

    /** Settle de falha: o turno fica FAILED e visível (o erro detalhado vive no StepTrace do Run). */
    public void fail() {
        this.status = TurnStatus.FAILED;
        this.answeredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getOwnerMessage() {
        return ownerMessage;
    }

    public String getCeoReply() {
        return ceoReply;
    }

    public UUID getRunId() {
        return runId;
    }

    public TurnStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }
}
