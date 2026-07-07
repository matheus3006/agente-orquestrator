package com.orquestrador.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma versão da memória durável da empresa (ADR-0018/0019): a CARTA é soberana e DERIVADA —
 * escrita SÓ pelo {@code CompanyMemoryConsolidator} (projeção determinística das Decisões, zero
 * LLM no caminho de escrita; defesa contra memory-poisoning). APPEND-ONLY por versão (savelog,
 * como {@code company_skill_version}): consolidar = nova versão; a corrente é a de maior versão.
 */
@Entity
@Table(name = "company_memory", uniqueConstraints = @UniqueConstraint(
        name = "uq_company_memory_kind_version", columnNames = {"company_id", "kind", "version"}))
public class CompanyMemory {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CompanyMemory() {
    }

    public CompanyMemory(UUID id, UUID companyId, MemoryKind kind, String body, int version) {
        this.id = id;
        this.companyId = companyId;
        this.kind = kind;
        this.body = body;
        this.version = version;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public MemoryKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
