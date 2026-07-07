package com.orquestrador.org.agent;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.OrchestrationAction;
import com.orquestrador.org.roletemplate.RoleTemplateService;
import com.orquestrador.skills.CompanySkill;
import com.orquestrador.skills.Skill;
import com.orquestrador.skills.SkillInstallService;
import com.orquestrador.skills.SkillRepository;
import com.orquestrador.skills.SkillSetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Contratação (hire) como módulo profundo (ADR-0010): esconde a orquestração de "contratar-com-role"
 * atrás de um seam pequeno. Sem molde → comportamento atual (motor avulso, sem seleção). Com
 * {@code roleTemplateVersionId} → COPIA o snapshot do preset (ADR-0017): motor + SkillSet + pin.
 *
 * <p><b>Override do operador VENCE e NÃO propaga ao molde:</b> os campos de motor passados à parte
 * sobrescrevem os do preset (o preset é só o default do hire); o molde é apenas LIDO, nunca mutado.
 * <b>Skillset:</b> cada {@code skill_key} do preset é instalado do catálogo GLOBAL (install-on-catalog)
 * e selecionado para o agente; {@code skill_key} inexistente é PULADO (o casamento conhecimento→skill e
 * o sinal de skill faltante são T.1.b/KnowAll). <b>reports_to_default</b> do preset é texto informativo —
 * NÃO liga em {@code agent.reports_to_id} nesta fatia (a cadeia de comando liga em T.2).
 */
@Service
public class AgentHireService {

    private final AgentRepository agents;
    private final RoleTemplateService roleTemplates;
    private final SkillRepository skills;
    private final SkillInstallService skillInstall;
    private final SkillSetService skillSets;
    private final BudgetService budgets;

    public AgentHireService(AgentRepository agents, RoleTemplateService roleTemplates, SkillRepository skills,
                            SkillInstallService skillInstall, SkillSetService skillSets,
                            BudgetService budgets) {
        this.agents = agents;
        this.roleTemplates = roleTemplates;
        this.skills = skills;
        this.skillSets = skillSets;
        this.skillInstall = skillInstall;
        this.budgets = budgets;
    }

    @Transactional
    public Agent hire(UUID companyId, HireCommand command, ActorType actorType, UUID actorId) {
        RoleTemplateService.Preset preset = command.roleTemplateVersionId() == null
                ? null
                : roleTemplates.resolvePreset(command.roleTemplateVersionId());

        // T.2 ligou a cadeia de comando: reports_to no hire agora APONTA de verdade — um líder
        // inexistente/de outra company é recusado (antes o campo aceitava qualquer UUID solto).
        if (command.reportsToId() != null) {
            agents.findById(command.reportsToId())
                    .filter(lider -> lider.getCompanyId().equals(companyId))
                    .orElseThrow(() -> new NotFoundException(
                            "Lider (reportsTo) nao encontrado na company: " + command.reportsToId()));
        }

        Agent agent = new Agent(UUID.randomUUID(), companyId, command.name(), command.role(), command.reportsToId());
        // Motor: o operador vence o preset campo-a-campo; ausente no operador cai no preset; ausente nos dois
        // = default do plano. Um único configureRuntime/configureEngine com os valores já mesclados (chamar
        // configureEngine duas vezes zeraria o model do preset quando o operador o omite).
        String adapterType = firstNonBlank(command.adapterType(), preset == null ? null : preset.adapterType());
        String model = firstNonBlank(command.model(), preset == null ? null : preset.model());
        String effort = firstNonBlank(command.effort(), preset == null ? null : preset.effort());
        agent.configureRuntime(adapterType, command.runtimeCommand(), command.executionTarget());
        agent.configureEngine(null, model, effort);
        if (preset != null) {
            agent.pinTemplateVersion(command.roleTemplateVersionId());
        }
        Agent saved = agents.save(agent);

        // O ato de SPAWNAR entra no ledger de orquestração NA ABERTURA (T.4, anti-enxame), na
        // conta de quem CAUSOU o hire (o agente proponente via approval; nulo = mão humana direta).
        budgets.recordOrchestration(companyId, command.causedByAgentId(), null,
                OrchestrationAction.AGENT_SPAWN);

        if (preset != null) {
            copySkillSet(companyId, saved.getId(), preset, actorType, actorId);
        }
        return saved;
    }

    /** Instala do catálogo GLOBAL e seleciona cada skill do preset; skill_key inexistente é pulado (T.1.b). */
    private void copySkillSet(UUID companyId, UUID agentId, RoleTemplateService.Preset preset,
                              ActorType actorType, UUID actorId) {
        for (String skillKey : preset.skillKeys()) {
            Skill skill = skills.findBySkillKey(skillKey).orElse(null);
            if (skill == null) {
                continue; // conhecimento sem skill no catálogo — sinalizar é T.1.b (KnowAll)
            }
            CompanySkill installed = skillInstall.installFromCatalog(companyId, skill.getId(), actorType, actorId);
            skillSets.select(companyId, agentId, installed.getId());
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    /**
     * O que o hire precisa saber — desacopla o serviço do record de request do controller.
     * {@code causedByAgentId} = o agente que CAUSOU a contratação (o proponente da approval);
     * nulo = mão soberana do operador. É a conta do AGENT_SPAWN no ledger de orquestração (T.4).
     */
    public record HireCommand(String name, String role, UUID reportsToId, String adapterType,
                              String runtimeCommand, String executionTarget, String model, String effort,
                              UUID roleTemplateVersionId, UUID causedByAgentId) {
    }
}
