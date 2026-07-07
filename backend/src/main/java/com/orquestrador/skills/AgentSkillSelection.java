package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * SkillSet base de um Agent (ADR-0003): uma skill da biblioteca da company selecionada para o agente,
 * PINANDO uma {@link CompanySkillVersion}. É o que é materializado por padrão em cada Run. A unique
 * (agent_id, company_skill_id) impede seleção duplicada da mesma skill para o mesmo agente.
 */
@Entity
@Table(name = "agent_skill_selection", uniqueConstraints =
        @UniqueConstraint(name = "uq_ass_agent_skill", columnNames = {"agent_id", "company_skill_id"}))
public class AgentSkillSelection {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "company_skill_id", nullable = false)
    private UUID companySkillId;

    @Column(name = "pinned_version_id", nullable = false)
    private UUID pinnedVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AgentSkillSelection() {
    }

    public AgentSkillSelection(UUID id, UUID companyId, UUID agentId, UUID companySkillId,
                               UUID pinnedVersionId) {
        this.id = id;
        this.companyId = companyId;
        this.agentId = agentId;
        this.companySkillId = companySkillId;
        this.pinnedVersionId = pinnedVersionId;
        this.createdAt = Instant.now();
    }

    /** Re-pina para outra versão (ex.: re-seleção aponta a versão corrente da skill). */
    public void repin(UUID pinnedVersionId) {
        this.pinnedVersionId = pinnedVersionId;
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

    public UUID getCompanySkillId() {
        return companySkillId;
    }

    public UUID getPinnedVersionId() {
        return pinnedVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
