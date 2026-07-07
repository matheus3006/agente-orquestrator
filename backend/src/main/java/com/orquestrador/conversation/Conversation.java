package com.orquestrador.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Um chat nomeado por ASSUNTO entre o board e o CEO (ADR-0018): "Geral", "Desenvolvimento",
 * "Marketing"… — todos com o MESMO CEO (muda o assunto, não o interlocutor; a organização é pro
 * humano). O board fala SÓ com o CEO (cadeia de comando, ADR-0017); a Conversation é a unidade de
 * continuidade da sessão do adapter ({@code agent_session} é por Agent+Conversation, ADR-0002).
 */
@Entity
@Table(name = "conversation", uniqueConstraints = @UniqueConstraint(
        name = "uq_conversation_company_subject", columnNames = {"company_id", "subject"}))
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String subject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Conversation() {
    }

    public Conversation(UUID id, UUID companyId, String subject) {
        this.id = id;
        this.companyId = companyId;
        this.subject = subject;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
