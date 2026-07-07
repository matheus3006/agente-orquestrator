package com.orquestrador.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento do ciclo de vida de um Run (RUN_STARTED, RUN_SUCCEEDED, RUN_FAILED, RUN_RECOVERED, …).
 * Semente do StepTrace observavel das proximas slices.
 */
@Entity
@Table(name = "heartbeat_run_event")
public class HeartbeatRunEvent {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String type;

    @Column
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected HeartbeatRunEvent() {
    }

    public HeartbeatRunEvent(UUID id, UUID runId, UUID companyId, String type, String detail) {
        this.id = id;
        this.runId = runId;
        this.companyId = companyId;
        this.type = type;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
