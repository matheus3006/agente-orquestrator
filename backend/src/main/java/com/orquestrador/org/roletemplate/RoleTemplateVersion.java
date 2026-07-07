package com.orquestrador.org.roletemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * SAVELOG imutável de um {@link RoleTemplate} (ADR-0017): uma versão do preset. Editar o molde = anexar
 * uma NOVA versão (nunca mutar); cada agente pina uma versão ({@code agent.pinned_template_version_id}),
 * então editar não mexe em quem já roda. Espelha {@link com.orquestrador.skills.SkillVersion}.
 *
 * <p>O preset carrega: o <b>MOTOR</b> como TRIO ({@code adapterType}/{@code model}/{@code effort} —
 * desacoplado do {@code engine_catalog}, ADR-0014), o <b>SKILLSET</b> ({@code skillset} jsonb: refs
 * {@code skill_key} do catálogo GLOBAL) e o <b>{@code reportsToDefault}</b> (tipo/role SUGERIDO, texto —
 * NÃO um agent id; a cadeia liga em T.2). {@code status} DRAFT/APPROVED (a governança de molde reusa a
 * fila de Review — no v1 board-direct nasce APPROVED). A unique {@code (role_template_id, version)} é
 * DECLARADA aqui (lição das fatias: H2 força pelo entity, sem drift).
 */
@Entity
@Table(name = "role_template_version", uniqueConstraints =
        @UniqueConstraint(name = "uq_rtv_version", columnNames = {"role_template_id", "version"}))
public class RoleTemplateVersion {

    @Id
    private UUID id;

    @Column(name = "role_template_id", nullable = false)
    private UUID roleTemplateId;

    @Column(nullable = false)
    private int version;

    @Column(name = "adapter_type", columnDefinition = "text")
    private String adapterType;

    @Column(columnDefinition = "text")
    private String model;

    @Column(columnDefinition = "text")
    private String effort;

    // Sem columnDefinition: o dialeto escolhe o tipo nativo (Postgres jsonb via V14; H2 json nos testes).
    // Espelha ActivityLog.detail — String já serializada, (de)serializada pelo RoleTemplateService.
    @JdbcTypeCode(SqlTypes.JSON)
    private String skillset;

    @Column(name = "reports_to_default", columnDefinition = "text")
    private String reportsToDefault;

    @Column(name = "change_type", columnDefinition = "text")
    private String changeType;

    @Column(name = "change_description", columnDefinition = "text")
    private String changeDescription;

    @Column(name = "proposed_by")
    private UUID proposedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(nullable = false)
    private String status = "APPROVED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RoleTemplateVersion() {
    }

    public RoleTemplateVersion(UUID id, UUID roleTemplateId, int version, String adapterType, String model,
                               String effort, String skillset, String reportsToDefault, String changeType,
                               String changeDescription, UUID proposedBy, UUID approvedBy, String status) {
        this.id = id;
        this.roleTemplateId = roleTemplateId;
        this.version = version;
        this.adapterType = adapterType;
        this.model = model;
        this.effort = effort;
        this.skillset = skillset;
        this.reportsToDefault = reportsToDefault;
        this.changeType = changeType;
        this.changeDescription = changeDescription;
        this.proposedBy = proposedBy;
        this.approvedBy = approvedBy;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoleTemplateId() {
        return roleTemplateId;
    }

    public int getVersion() {
        return version;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public String getModel() {
        return model;
    }

    public String getEffort() {
        return effort;
    }

    public String getSkillset() {
        return skillset;
    }

    public String getReportsToDefault() {
        return reportsToDefault;
    }

    public String getChangeType() {
        return changeType;
    }

    public String getChangeDescription() {
        return changeDescription;
    }

    public UUID getProposedBy() {
        return proposedBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
