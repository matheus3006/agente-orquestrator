package com.orquestrador.context.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Linha append-only: findings são enums + hashes; prompts, respostas e corpos não são representáveis. */
@Entity
@Table(name = "context_assessment")
public class StoredContextAssessment {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContextAssessment.Status status;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_reason")
    private ContextAssessment.FallbackReason fallbackReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false)
    private String findingsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoredContextAssessment() {
    }

    StoredContextAssessment(ContextAssessmentStore.Observation observation, String findingsJson) {
        this.id = observation.id();
        this.runId = observation.runId();
        this.companyId = observation.companyId();
        this.mode = observation.mode();
        this.provider = observation.provider();
        this.model = observation.model();
        this.promptVersion = observation.promptVersion();
        this.sourceHash = observation.sourceHash();
        this.status = observation.assessment().status();
        this.cacheHit = observation.cacheHit();
        this.latencyMs = observation.latencyMs();
        this.inputTokens = observation.inputTokens();
        this.outputTokens = observation.outputTokens();
        this.fallbackReason = observation.fallbackReason();
        this.findingsJson = findingsJson;
        this.createdAt = observation.createdAt();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public UUID getCompanyId() { return companyId; }
    public String getMode() { return mode; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPromptVersion() { return promptVersion; }
    public String getSourceHash() { return sourceHash; }
    public ContextAssessment.Status getStatus() { return status; }
    public boolean isCacheHit() { return cacheHit; }
    public long getLatencyMs() { return latencyMs; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public ContextAssessment.FallbackReason getFallbackReason() { return fallbackReason; }
    public String getFindingsJson() { return findingsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
