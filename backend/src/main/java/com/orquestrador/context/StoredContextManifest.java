package com.orquestrador.context;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.MemoryAudience;
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

/** Projeção persistida, 1:1 com o Run, do manifesto sem corpos compilado para execução. */
@Entity
@Table(name = "run_context_manifest")
public class StoredContextManifest {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "issue_id")
    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryAudience audience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionTarget.Type target;

    @Column(nullable = false)
    private String convention;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "compiled_at", nullable = false)
    private Instant compiledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContextManifest.Status status;

    @Column(name = "final_hash", nullable = false, length = 64)
    private String finalHash;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "estimated_tokens", nullable = false)
    private long estimatedTokens;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", nullable = false)
    private String sourcesJson;

    protected StoredContextManifest() {
    }

    StoredContextManifest(ContextManifest manifest, String sourcesJson) {
        this.runId = manifest.runId();
        this.companyId = manifest.companyId();
        this.agentId = manifest.agentId();
        this.issueId = manifest.issueId();
        this.audience = manifest.audience();
        this.target = manifest.target();
        this.convention = manifest.convention();
        this.schemaVersion = manifest.schemaVersion();
        this.compiledAt = manifest.compiledAt();
        this.status = manifest.status();
        this.finalHash = manifest.finalHash();
        this.totalBytes = manifest.totalBytes();
        this.estimatedTokens = manifest.estimatedTokens();
        this.sourcesJson = sourcesJson;
    }

    public UUID getRunId() { return runId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getAgentId() { return agentId; }
    public UUID getIssueId() { return issueId; }
    public MemoryAudience getAudience() { return audience; }
    public ExecutionTarget.Type getTarget() { return target; }
    public String getConvention() { return convention; }
    public int getSchemaVersion() { return schemaVersion; }
    public Instant getCompiledAt() { return compiledAt; }
    public ContextManifest.Status getStatus() { return status; }
    public String getFinalHash() { return finalHash; }
    public long getTotalBytes() { return totalBytes; }
    public long getEstimatedTokens() { return estimatedTokens; }
    public String getSourcesJson() { return sourcesJson; }
}
