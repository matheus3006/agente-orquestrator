package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Identidade GLOBAL de uma Skill no catálogo do KnowAll (Camada 1, ADR-0008) — sem {@code company_id}.
 * A chave estável é {@code skill_key} (o {@code name} do frontmatter). Carrega metadados denormalizados
 * (name/description/triggers) e o ponteiro para a versão-base corrente ({@link SkillVersion}) para a
 * busca. O histórico de versões-base vive em {@link SkillVersion} (savelog); o conteúdo instalado numa
 * Company vive em {@link CompanySkill}/{@link CompanySkillVersion} (Camada 2).
 */
@Entity
@Table(name = "skill", uniqueConstraints =
        @UniqueConstraint(name = "uq_skill_key", columnNames = {"skill_key"}))
public class Skill {

    @Id
    private UUID id;

    @Column(name = "skill_key", nullable = false)
    private String skillKey;

    @Column(columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String triggers;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Skill() {
    }

    public Skill(UUID id, String skillKey) {
        this.id = id;
        this.skillKey = skillKey;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Aponta a versão-base corrente e atualiza os metadados denormalizados usados na busca. */
    public void promoteBaseVersion(UUID currentVersionId, String name, String description, String triggers) {
        this.currentVersionId = currentVersionId;
        this.name = name;
        this.description = description;
        this.triggers = triggers;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSkillKey() {
        return skillKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTriggers() {
        return triggers;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
