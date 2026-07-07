package com.orquestrador.activitylog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável (append-only) de uma ação mutante — invariante #5, ADR-0009. HÍBRIDO: colunas
 * tipadas (ator/ação/entidade) + {@code detail} JSON com só IDs/enums/números (nunca body/segredo/PII).
 * Imutabilidade real é garantida no Postgres (trigger da V8); o writer é INSERT-only por design.
 */
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    // Sem columnDefinition: o dialeto escolhe o tipo nativo (Postgres jsonb via V8; H2 json nos testes).
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ActivityLog() {
    }

    public ActivityLog(UUID id, UUID companyId, String actorType, UUID actorId, String action,
                       String entityType, UUID entityId, String detail) {
        this.id = id;
        this.companyId = companyId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
