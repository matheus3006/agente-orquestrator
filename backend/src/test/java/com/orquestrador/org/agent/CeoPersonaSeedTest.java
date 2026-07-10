package com.orquestrador.org.agent;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.roletemplate.RoleTemplate;
import com.orquestrador.org.roletemplate.RoleTemplateRepository;
import com.orquestrador.org.roletemplate.RoleTemplateService;
import com.orquestrador.org.roletemplate.RoleTemplateVersionRepository;
import com.orquestrador.org.roletemplate.TipoFuncao;
import com.orquestrador.skills.CompanySkillVersion;
import com.orquestrador.skills.Skill;
import com.orquestrador.skills.SkillRepository;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.skills.SkillVersion;
import com.orquestrador.skills.SkillVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-seed das 3 personas de CEO (ADR-0031, D1/D9): as personas NASCEM DO SISTEMA na criação da
 * company — 3 linhas {@code agent role='ceo'} distintas por {@code persona}, cada uma copiando o
 * molde-de-sistema da persona (motor + skill-set + pin, via o MESMO hire da ADR-0017).
 *
 * <p>O seed é DIRIGIDO PELOS MOLDES e fail-soft: banco sem os moldes {@code ceo-*} (H2 dos testes,
 * que roda sem Flyway/V25) não semeia nada — companies de teste legadas seguem nascendo vazias.
 */
@SpringBootTest
@ActiveProfiles("test")
class CeoPersonaSeedTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private RoleTemplateService roleTemplates;
    @Autowired
    private RoleTemplateRepository templates;
    @Autowired
    private RoleTemplateVersionRepository templateVersions;
    @Autowired
    private SkillRepository skills;
    @Autowired
    private SkillVersionRepository skillVersions;
    @Autowired
    private SkillSetService skillSets;

    /** Moldes são GLOBAIS e o H2 do contexto é compartilhado — remover no fim evita vazar o seed. */
    private final List<UUID> moldesCriados = new ArrayList<>();

    @AfterEach
    void removeMoldesGlobais() {
        for (UUID templateId : moldesCriados) {
            templateVersions.deleteAll(templateVersions.findByRoleTemplateIdOrderByVersionDesc(templateId));
            templates.deleteById(templateId);
        }
        moldesCriados.clear();
    }

    @Test
    void criarCompanySemeiaAsTresPersonasDeCeoAPartirDosMoldes() {
        seedGlobalSkill("being-ceo");
        criaMoldeDePersona(CeoPersona.QUESTIONADOR, List.of("being-ceo"));
        criaMoldeDePersona(CeoPersona.PLANEJADOR, List.of("being-ceo", "writing-doc-plans"));
        criaMoldeDePersona(CeoPersona.EXECUTOR, List.of("delegation"));

        UUID companyId = novaCompany();

        List<Agent> ceos = agents.findByCompanyId(companyId).stream()
                .filter(agent -> "ceo".equals(agent.getRole()))
                .toList();
        assertThat(ceos).hasSize(3);
        assertThat(ceos).extracting(Agent::getPersona)
                .containsExactlyInAnyOrder("questionador", "planejador", "executor");

        // Cada persona copiou o SEU molde: motor do preset + pin + skill-set (resolveForRun já é
        // por-persona de graça — 3 agentes distintos, cada um com seu agent_skill_selection, D1).
        Agent questionador = personaDe(ceos, "questionador");
        assertThat(questionador.getAdapterType()).isEqualTo("claude-code");
        assertThat(questionador.getPinnedTemplateVersionId()).isNotNull();
        assertThat(skillSets.resolveForAgent(companyId, questionador.getId()))
                .extracting(CompanySkillVersion::getName)
                .containsExactly("being-ceo");

        // skill_key sem corpo no catálogo (autoria é a faixa R1-D) é PULADA, não trava o seed.
        Agent executor = personaDe(ceos, "executor");
        assertThat(skillSets.resolveForAgent(companyId, executor.getId())).isEmpty();
    }

    @Test
    void bancoSemMoldesDeCeoNaoSemeiaNada_failSoft() {
        UUID companyId = novaCompany();
        assertThat(agents.findByCompanyId(companyId)).isEmpty();
    }

    // --- helpers ---

    private UUID novaCompany() {
        return companies.create("Trias", "trias-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private static Agent personaDe(List<Agent> ceos, String persona) {
        return ceos.stream().filter(agent -> persona.equals(agent.getPersona())).findFirst().orElseThrow();
    }

    private void criaMoldeDePersona(CeoPersona persona, List<String> skillKeys) {
        RoleTemplateService.Preset preset = new RoleTemplateService.Preset(
                "claude-code", "opus", "high", skillKeys, null);
        RoleTemplate template = roleTemplates.createTemplate(TipoFuncao.LIDERANCA,
                persona.moldeRole(), preset, "molde de sistema (teste)", UUID.randomUUID());
        moldesCriados.add(template.getId());
    }

    private void seedGlobalSkill(String skillKey) {
        String hash = UUID.randomUUID().toString().replace("-", "");
        Skill skill = skills.save(new Skill(UUID.randomUUID(), skillKey));
        SkillVersion version = skillVersions.save(new SkillVersion(UUID.randomUUID(), skill.getId(), 1, hash,
                skillKey, "desc", "triggers", "# body", "seed", "LOCAL_PATH", "seed"));
        skill.promoteBaseVersion(version.getId(), skillKey, "desc", "triggers");
        skills.save(skill);
    }
}
