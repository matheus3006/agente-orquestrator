package com.orquestrador.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * SAVELOG do catálogo GLOBAL (Camada 1, ADR-0007 revisado): uma versão imutável da base de uma
 * {@link Skill}. {@code body} = SKILL.md redigido (LGPD); {@code contentHash} = SHA-256 desse body.
 * {@code changeDescription} = auto na re-importação, à mão em ajuste deliberado. {@code origin} =
 * de onde veio a base (LOCAL_PATH no v1). As companies instalam COPIANDO uma versão daqui.
 */
@Entity
@Table(name = "skill_version", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sv_version", columnNames = {"skill_id", "version"}),
        @UniqueConstraint(name = "uq_sv_content_hash", columnNames = {"skill_id", "content_hash"})})
public class SkillVersion {

    @Id
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

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

    @Column(nullable = false)
    private String origin = "LOCAL_PATH";

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SkillVersion() {
    }

    public SkillVersion(UUID id, UUID skillId, int version, String contentHash, String name,
                        String description, String triggers, String body, String changeDescription,
                        String origin, String sourceRef) {
        this.id = id;
        this.skillId = skillId;
        this.version = version;
        this.contentHash = contentHash;
        this.name = name;
        this.description = description;
        this.triggers = triggers;
        this.body = body;
        this.changeDescription = changeDescription;
        this.origin = origin;
        this.sourceRef = sourceRef;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSkillId() {
        return skillId;
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

    public String getOrigin() {
        return origin;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
