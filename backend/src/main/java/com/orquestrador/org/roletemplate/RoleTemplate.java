package com.orquestrador.org.roletemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Identidade de um MOLDE (role-template) — o preset reutilizável (ADR-0017). Espelha a identidade GLOBAL
 * de {@link com.orquestrador.skills.Skill}: {@code company_id} NULL = global (no v1 é SEMPRE global; a
 * coluna fica para o override por-company de T.2). {@code roleEspecifica} é a chave estável do catálogo
 * aberto ("architect"/"executor"/...); {@code tipoFuncao} é o eixo fixo. O histórico do preset vive em
 * {@link RoleTemplateVersion} (savelog append-only); {@code currentVersionId} aponta a versão corrente.
 *
 * <p>A unicidade da role global ({@code role_especifica} where {@code company_id is null}) é um índice
 * PARCIAL no Postgres (NULL é distinto em unique) — o H2 não a enforça, então o service faz pré-check.
 */
@Entity
@Table(name = "role_template")
public class RoleTemplate {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_funcao", nullable = false)
    private TipoFuncao tipoFuncao;

    @Column(name = "role_especifica", nullable = false, columnDefinition = "text")
    private String roleEspecifica;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RoleTemplate() {
    }

    public RoleTemplate(UUID id, TipoFuncao tipoFuncao, String roleEspecifica, UUID companyId) {
        this.id = id;
        this.tipoFuncao = tipoFuncao;
        this.roleEspecifica = roleEspecifica;
        this.companyId = companyId;
        this.createdAt = Instant.now();
    }

    /** Aponta a versão corrente do preset (após criar a v1 ou anexar uma nova versão). */
    public void promoteCurrentVersion(UUID currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public UUID getId() {
        return id;
    }

    public TipoFuncao getTipoFuncao() {
        return tipoFuncao;
    }

    public String getRoleEspecifica() {
        return roleEspecifica;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
