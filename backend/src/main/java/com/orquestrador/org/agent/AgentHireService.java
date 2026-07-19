package com.orquestrador.org.agent;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.OrchestrationAction;
import com.orquestrador.org.roletemplate.RoleTemplateService;
import com.orquestrador.org.team.TeamService;
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
    private final TeamService teams;

    public AgentHireService(AgentRepository agents, RoleTemplateService roleTemplates, SkillRepository skills,
                            SkillInstallService skillInstall, SkillSetService skillSets,
                            BudgetService budgets, TeamService teams) {
        this.agents = agents;
        this.roleTemplates = roleTemplates;
        this.skills = skills;
        this.skillSets = skillSets;
        this.skillInstall = skillInstall;
        this.budgets = budgets;
        this.teams = teams;
    }

    /**
     * A PORTA NORMAL de contratação — a única que a API HTTP e o gate de aprovação alcançam, e ela
     * <b>EXIGE TIME</b> (ADR-0045, D-ORG-9: "nada órfão"). A chefia não é parâmetro: ela DERIVA do
     * time (o agente nasce com o espelho já preenchido). Quem pode nascer na raiz não é decidido
     * por um atributo do agente — é decidido pela PORTA (ver {@link #hireAtRoot}), e por isso
     * digitar {@code role="ceo"} aqui não fura nada.
     */
    @Transactional
    public Agent hire(UUID companyId, HireCommand command, ActorType actorType, UUID actorId) {
        if (command.teamId() == null) {
            throw new ConflictException("todo agente contratado pertence a um time (ADR-0045):"
                    + " informe o time no hire — só o CEO e as personas moram na raiz");
        }
        return contratar(companyId, command, command.teamId(), actorType, actorId);
    }

    /**
     * A PORTA DA RAIZ: contrata SEM time, para os moradores da raiz (CEO + as 3 personas). Chamada
     * só pelo {@link CeoPersonaSeeder} no nascimento da company — não tem rota HTTP nem bloco de
     * proposta que chegue aqui. É a exceção da D-ORG-9 CODIFICADA como caminho, não como {@code if}
     * sobre um campo de texto que o operador digita.
     */
    @Transactional
    public Agent hireAtRoot(UUID companyId, HireCommand command, ActorType actorType, UUID actorId) {
        return contratar(companyId, command, null, actorType, actorId);
    }

    private Agent contratar(UUID companyId, HireCommand command, UUID teamId, ActorType actorType,
                            UUID actorId) {
        RoleTemplateService.Preset preset = command.roleTemplateVersionId() == null
                ? null
                : roleTemplates.resolvePreset(command.roleTemplateVersionId());

        // reportsTo nasce NULO e é derivado logo abaixo pelo assignAgent (ADR-0045) — a chefia é
        // espelho da árvore, nunca entrada. Sem time (porta da raiz) o nulo já é a resposta final.
        Agent agent = new Agent(UUID.randomUUID(), companyId, command.name(), command.role(), null);
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
        if (teamId != null) {
            // valida time (existe + é da company) E deriva a chefia no mesmo ato — o TeamService é
            // o dono dos dois invariantes; duplicá-los aqui criaria a segunda verdade que a
            // ADR-0045 mata.
            saved = teams.assignAgent(companyId, saved.getId(), teamId);
        }

        // O ato de SPAWNAR entra no ledger de orquestração NA ABERTURA (T.4, anti-enxame), na
        // conta de quem CAUSOU o hire (o agente proponente via approval; nulo = mão humana direta).
        budgets.recordOrchestration(companyId, command.causedByAgentId(), null,
                OrchestrationAction.AGENT_SPAWN);

        if (preset != null) {
            copySkillSet(companyId, saved.getId(), preset, actorType, actorId);
        }
        return saved;
    }

    /**
     * Re-pin COMPLETO (R10-A/D5-D6): re-aplica o preset da versão (motor + skillset) e move o pin.
     * SUBSTITUI — a versão manda: o motor da versão sobrescreve o do agente (inclusive um override
     * manual do operador) e o skillset é RESETADO para o da versão (as seleções atuais são limpas
     * antes de re-aplicar). Sem isso, um botão "atualizar" que só movesse o ponteiro mentiria no
     * drift-badge. NÃO é gate: é mão soberana do operador (ação LOCAL — um agente, uma company);
     * espelha o hire (mesmo {@link #copySkillSet}), mas NÃO cobra AGENT_SPAWN (re-pin não é spawn).
     */
    @Transactional
    public Agent repin(UUID companyId, UUID agentId, UUID versionId, ActorType actorType, UUID actorId) {
        RoleTemplateService.Preset preset = roleTemplates.resolvePreset(versionId); // NotFound se sumiu
        Agent agent = agents.findById(agentId).filter(candidate -> companyId.equals(candidate.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado na company: " + agentId));
        // Motor: a versão manda (configureEngine sobrescreve model/effort; adapterType não-branco troca o plano).
        agent.configureEngine(preset.adapterType(), preset.model(), preset.effort());
        agent.pinTemplateVersion(versionId);
        agents.save(agent);
        // Skillset SUBSTITUÍDO: limpa o set base atual e re-aplica exatamente o da versão.
        skillSets.clearForAgent(companyId, agentId);
        copySkillSet(companyId, agentId, preset, actorType, actorId);
        return agent;
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
     * {@code teamId} = o TIME de destino (ADR-0045): obrigatório na porta normal, ignorado na porta
     * da raiz. Note que <b>não existe {@code reportsToId} aqui</b>: a chefia deriva da árvore, e um
     * campo que aceitasse chefe seria convite para a segunda verdade.
     * {@code causedByAgentId} = o agente que CAUSOU a contratação (o proponente da approval);
     * nulo = mão soberana do operador. É a conta do AGENT_SPAWN no ledger de orquestração (T.4).
     */
    public record HireCommand(String name, String role, UUID teamId, String adapterType,
                              String runtimeCommand, String executionTarget, String model, String effort,
                              UUID roleTemplateVersionId, UUID causedByAgentId) {
    }
}
