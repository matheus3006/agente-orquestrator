package com.orquestrador.org.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent")
public class Agent {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(name = "reports_to_id")
    private UUID reportsToId;

    @Column(name = "adapter_type", nullable = false)
    private String adapterType = "process";

    @Column(name = "runtime_command")
    private String runtimeCommand;

    @Column(name = "execution_target", nullable = false)
    private String executionTarget = "LOCAL";

    // Motor (ADR-0014): flags que o adapter traduz para o CLI do runtime. Nullable —
    // null = default do plano (o adapter omite a flag). O adapterType é que decide o PLANO.
    @Column(name = "model")
    private String model;

    @Column(name = "effort")
    private String effort;

    // Molde pinado (ADR-0017): a versão de role-template que este agente copiou no hire. Nullable —
    // null = agente sem molde (hire avulso). Re-pin explícito (reversível) troca a versão; NÃO
    // re-aplica o snapshot (o preset só é copiado no hire — ajuste avulso é override do operador).
    @Column(name = "pinned_template_version_id")
    private UUID pinnedTemplateVersionId;

    // Time (ADR-0017, T.2): NULL = fora de time (dispatch livre — a trava do arquiteto é DO TIME).
    // Mover de time passa pelo TeamService (que mantém o invariante arquiteto-é-membro).
    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Agent() {
    }

    public Agent(UUID id, UUID companyId, String name, String role, UUID reportsToId) {
        this.id = id;
        this.companyId = companyId;
        this.name = name;
        this.role = role;
        this.reportsToId = reportsToId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public UUID getReportsToId() {
        return reportsToId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public String getRuntimeCommand() {
        return runtimeCommand;
    }

    public String getExecutionTarget() {
        return executionTarget;
    }

    public String getModel() {
        return model;
    }

    public String getEffort() {
        return effort;
    }

    public UUID getPinnedTemplateVersionId() {
        return pinnedTemplateVersionId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    /** Pina (ou re-pina, reversível) a versão do molde que este agente segue (ADR-0017). */
    public void pinTemplateVersion(UUID pinnedTemplateVersionId) {
        this.pinnedTemplateVersionId = pinnedTemplateVersionId;
    }

    /** Move o agente de time (nulo = sai). Invariantes de composição vivem no TeamService. */
    public void assignTeam(UUID teamId) {
        this.teamId = teamId;
    }

    /** Religa o líder direto (nulo = raiz). O anti-ciclo vive no ChainOfCommandService. */
    public void reassignReportsTo(UUID reportsToId) {
        this.reportsToId = reportsToId;
    }

    /** Configura o runtime do agente; valores nulos/em-branco mantem os defaults. */
    public void configureRuntime(String adapterType, String runtimeCommand, String executionTarget) {
        if (adapterType != null && !adapterType.isBlank()) {
            this.adapterType = adapterType;
        }
        if (runtimeCommand != null) {
            this.runtimeCommand = runtimeCommand;
        }
        if (executionTarget != null && !executionTarget.isBlank()) {
            this.executionTarget = executionTarget;
        }
    }

    /**
     * Configura o MOTOR (ADR-0014): {@code adapterType} em-branco preserva o atual (decide o plano);
     * {@code model}/{@code effort} SOBRESCREVEM (o motor é escolhido inteiro no dropdown — promover
     * troca o trio). Em-branco vira {@code null} = default do plano (a borda só valida não-branco;
     * quem valida o modelo é o adapter contra o CLI).
     */
    public void configureEngine(String adapterType, String model, String effort) {
        if (adapterType != null && !adapterType.isBlank()) {
            this.adapterType = adapterType;
        }
        this.model = blankToNull(model);
        this.effort = blankToNull(effort);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
