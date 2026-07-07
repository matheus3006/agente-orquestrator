package com.orquestrador.org.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Catálogo CURADO de motores (ADR-0014, tabela V13). GLOBAL — sem {@code company_id}, como o catálogo de
 * Skills (Camada 1). Cada linha é uma combinação {@code adapterType + model + effort} com um {@code label}
 * legível e a {@code contextWindow} (denominador do handoff a 50-60%, ADR-0015). SUGERE para a UI, NÃO
 * restringe o dado — quem valida o modelo é o adapter contra o CLI (ADR-0014/Q2). Modelo novo = INSERT.
 */
@Entity
@Table(name = "engine_catalog")
public class EngineCatalog {

    @Id
    private UUID id;

    @Column(name = "adapter_type", nullable = false)
    private String adapterType;

    @Column
    private String model;

    @Column
    private String effort;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EngineCatalog() {
    }

    public EngineCatalog(UUID id, String adapterType, String model, String effort,
                         Integer contextWindow, String label, boolean enabled) {
        this.id = id;
        this.adapterType = adapterType;
        this.model = model;
        this.effort = effort;
        this.contextWindow = contextWindow;
        this.label = label;
        this.enabled = enabled;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
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

    public Integer getContextWindow() {
        return contextWindow;
    }

    public String getLabel() {
        return label;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
