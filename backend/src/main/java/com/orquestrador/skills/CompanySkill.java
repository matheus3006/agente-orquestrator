package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Skill INSTALADA numa Company — a biblioteca por-empresa (install != attach, ADR-0003).
 * Guarda a origem (de onde veio) e os hashes de controle de drift. A versao corrente e a
 * {@link CompanySkillVersion} cujo {@code contentHash} casa com {@link #getContentHash()}.
 */
@Entity
@Table(name = "company_skill", uniqueConstraints =
        @UniqueConstraint(name = "uq_company_skill", columnNames = {"company_id", "skill_id"}))
public class CompanySkill {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @Column(name = "origin_hash")
    private String originHash;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private String state = "INSTALLED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CompanySkill() {
    }

    public CompanySkill(UUID id, UUID companyId, UUID skillId, String source, String sourceRef,
                        String originHash, String contentHash) {
        this.id = id;
        this.companyId = companyId;
        this.skillId = skillId;
        this.source = source;
        this.sourceRef = sourceRef;
        this.originHash = originHash;
        this.contentHash = contentHash;
        this.state = "INSTALLED";
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Promove uma nova versao instalada: atualiza hashes/origem e carimba updatedAt. */
    public void promoteVersion(String originHash, String contentHash, String sourceRef) {
        this.originHash = originHash;
        this.contentHash = contentHash;
        if (sourceRef != null) {
            this.sourceRef = sourceRef;
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public String getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getOriginHash() {
        return originHash;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
