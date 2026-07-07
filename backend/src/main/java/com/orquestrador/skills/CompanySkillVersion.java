package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Versão imutável do conteúdo de uma {@link CompanySkill} (Camada 2). Copiada de uma
 * {@link SkillVersion} global no install ({@code sourceGlobalVersionId} rastreia a origem).
 * {@code body} = SKILL.md redigido (LGPD); {@code contentHash} = SHA-256 desse body. Uma versão
 * nova só nasce quando o body redigido muda (idempotência). As uniques (espelham o V6/V7) impedem
 * versão duplicada por hash e por número — inclusive no H2.
 */
@Entity
@Table(name = "company_skill_version", uniqueConstraints = {
        @UniqueConstraint(name = "uq_csv_content_hash", columnNames = {"company_skill_id", "content_hash"}),
        @UniqueConstraint(name = "uq_csv_version", columnNames = {"company_skill_id", "version"})})
public class CompanySkillVersion {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "company_skill_id", nullable = false)
    private UUID companySkillId;

    @Column(nullable = false)
    private int version;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(columnDefinition = "text", nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String triggers;

    @Column(columnDefinition = "text", nullable = false)
    private String body;

    @Column(name = "change_description", columnDefinition = "text")
    private String changeDescription;

    @Column(name = "source_global_version_id")
    private UUID sourceGlobalVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CompanySkillVersion() {
    }

    public CompanySkillVersion(UUID id, UUID companyId, UUID companySkillId, int version,
                               String contentHash, String name, String description, String triggers,
                               String body, String changeDescription, UUID sourceGlobalVersionId) {
        this.id = id;
        this.companyId = companyId;
        this.companySkillId = companySkillId;
        this.version = version;
        this.contentHash = contentHash;
        this.name = name;
        this.description = description;
        this.triggers = triggers;
        this.body = body;
        this.changeDescription = changeDescription;
        this.sourceGlobalVersionId = sourceGlobalVersionId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getCompanySkillId() {
        return companySkillId;
    }

    public int getVersion() {
        return version;
    }

    public String getContentHash() {
        return contentHash;
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

    public String getBody() {
        return body;
    }

    public String getChangeDescription() {
        return changeDescription;
    }

    public UUID getSourceGlobalVersionId() {
        return sourceGlobalVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
