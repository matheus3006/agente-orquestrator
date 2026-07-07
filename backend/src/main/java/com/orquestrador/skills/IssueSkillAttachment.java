package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Anexo de skill por-Issue (ADR-0003 revisado): PINA uma {@link CompanySkillVersion} instalada na
 * company ao escopo de uma Issue, para um Agent. Registro IMUTAVEL / append-only — nasce no anexo e
 * nunca muda; a "expiração" é do EFEITO (a materialização para de incluí-lo quando a Issue fecha),
 * não do registro. A unique (issue_id, company_skill_version_id) — espelhada da V10 para o H2 forçar —
 * torna o re-anexo da mesma versão idempotente.
 */
@Entity
@Table(name = "issue_skill_attachment", uniqueConstraints =
        @UniqueConstraint(name = "uq_isa_issue_version", columnNames = {"issue_id", "company_skill_version_id"}))
public class IssueSkillAttachment {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "issue_id", nullable = false)
    private UUID issueId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "company_skill_version_id", nullable = false)
    private UUID companySkillVersionId;

    @Column(name = "attached_by_actor", nullable = false)
    private UUID attachedByActor;

    @Column(name = "created_in_run_id")
    private UUID createdInRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected IssueSkillAttachment() {
    }

    public IssueSkillAttachment(UUID id, UUID companyId, UUID issueId, UUID agentId,
                                UUID companySkillVersionId, UUID attachedByActor, UUID createdInRunId) {
        this.id = id;
        this.companyId = companyId;
        this.issueId = issueId;
        this.agentId = agentId;
        this.companySkillVersionId = companySkillVersionId;
        this.attachedByActor = attachedByActor;
        this.createdInRunId = createdInRunId;
        this.createdAt = Instant.now();
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

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getCompanySkillVersionId() {
        return companySkillVersionId;
    }

    public UUID getAttachedByActor() {
        return attachedByActor;
    }

    public UUID getCreatedInRunId() {
        return createdInRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
