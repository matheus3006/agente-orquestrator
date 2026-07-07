package com.orquestrador.org.roletemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.skills.CompanySkillVersion;
import com.orquestrador.skills.Skill;
import com.orquestrador.skills.SkillRepository;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.skills.SkillVersion;
import com.orquestrador.skills.SkillVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hire-com-role (ADR-0017): contratar com {@code roleTemplateVersionId} COPIA o snapshot do preset —
 * motor (via {@code configureEngine}) + SkillSet (install-on-catalog + {@code SkillSetService.select})
 * + pin da versão. O OVERRIDE do operador (campo passado à parte) VENCE e NÃO propaga ao molde
 * (savelog intacto). Sem preset → comportamento atual (process default, sem seleção).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HireWithRoleTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private RoleTemplateService roleTemplates;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private SkillRepository skills;
    @Autowired
    private SkillVersionRepository skillVersions;

    @Test
    void hireComRoleCopiaMotorSkillsetEPinaAVersao() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = newCompany(board);
        String skillKey = seedGlobalSkill();
        UUID versionId = createTemplateVersion(board, "claude-code", "opus", "ultracode", List.of(skillKey));

        String body = "{\"name\":\"Lyra\",\"role\":\"architect\",\"roleTemplateVersionId\":\"" + versionId + "\"}";
        String response = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("claude-code")) // motor copiado do preset
                .andExpect(jsonPath("$.model").value("opus"))
                .andExpect(jsonPath("$.effort").value("ultracode"))
                .andExpect(jsonPath("$.pinnedTemplateVersionId").value(versionId.toString())) // pin
                .andReturn().getResponse().getContentAsString();
        UUID agentId = UUID.fromString(om.readTree(response).get("id").asText());

        // SkillSet copiado: a skill do preset foi instalada na company e selecionada para o agente
        List<CompanySkillVersion> selected = skillSets.resolveForAgent(companyId, agentId);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getName()).isEqualTo(skillKey);
    }

    @Test
    void overrideDoOperadorVenceENaoPropagaAoMolde() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = newCompany(board);
        UUID versionId = createTemplateVersion(board, "claude-code", "opus", "ultracode", List.of());

        // operador passa model=haiku à parte → vence o preset (opus)
        String body = "{\"name\":\"Echo\",\"role\":\"executor\",\"roleTemplateVersionId\":\"" + versionId + "\","
                + "\"model\":\"haiku\",\"effort\":\"medium\"}";
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("claude-code")) // adapter do preset (não sobrescrito)
                .andExpect(jsonPath("$.model").value("haiku"))   // override do operador
                .andExpect(jsonPath("$.effort").value("medium"));

        // molde INTACTO: o override não propagou ao savelog
        assertThat(roleTemplates.resolvePreset(versionId).model()).isEqualTo("opus");
        assertThat(roleTemplates.resolvePreset(versionId).effort()).isEqualTo("ultracode");
    }

    @Test
    void semPresetContinuaOComportamentoAtual_processDefault() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = newCompany(board);

        String response = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Plain\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("process"))
                .andExpect(jsonPath("$.pinnedTemplateVersionId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID agentId = UUID.fromString(om.readTree(response).get("id").asText());

        assertThat(skillSets.resolveForAgent(companyId, agentId)).isEmpty();
    }

    /**
     * Contratar é superfície do OPERADOR (deny-by-default): com {@code roleTemplateVersionId} o hire
     * dispara install de skill — board-only em todo o resto do sistema —, então agente autenticado
     * (member) não contrata: nem com molde, nem sem.
     */
    @Test
    void hireEBoardOnly_agenteAutenticadoLeva403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = newCompany(board);
        UUID versionId = createTemplateVersion(board, "claude-code", "opus", "ultracode",
                List.of(seedGlobalSkill()));

        String agenteContratado = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Interno\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID agenteContratadoId = UUID.fromString(om.readTree(agenteContratado).get("id").asText());
        String bearerDoAgente = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agenteContratadoId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        // hire-com-role como agente → 403 (senão burlaria o gate board-only do install de skill)
        String hireComMolde = "{\"name\":\"Burla\",\"role\":\"architect\",\"roleTemplateVersionId\":\""
                + versionId + "\"}";
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("Authorization", "Bearer " + bearerDoAgente)
                        .contentType(MediaType.APPLICATION_JSON).content(hireComMolde))
                .andExpect(status().isForbidden());

        // hire avulso como agente → 403 (contratar é do operador; spawn de agente é T.4, outra porta)
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("Authorization", "Bearer " + bearerDoAgente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BurlaSemMolde\",\"role\":\"worker\"}"))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private UUID createTemplateVersion(UUID board, String adapter, String model, String effort, List<String> skillKeys) {
        RoleTemplateService.Preset preset =
                new RoleTemplateService.Preset(adapter, model, effort, skillKeys, "PLANEJAMENTO_ARQUITETO");
        RoleTemplate template = roleTemplates.createTemplate(TipoFuncao.PLANEJAMENTO_ARQUITETO,
                "role-" + UUID.randomUUID().toString().substring(0, 8), preset, "seed", board);
        return template.getCurrentVersionId();
    }

    /** Semeia uma skill no catálogo GLOBAL (Camada 1) com versão-base corrente, para o hire instalar+selecionar. */
    private String seedGlobalSkill() {
        String skillKey = "skill-" + UUID.randomUUID().toString().substring(0, 8);
        String hash = UUID.randomUUID().toString().replace("-", "");
        Skill skill = skills.save(new Skill(UUID.randomUUID(), skillKey));
        SkillVersion version = skillVersions.save(new SkillVersion(UUID.randomUUID(), skill.getId(), 1, hash,
                skillKey, "desc", "triggers", "# body", "seed", "LOCAL_PATH", "seed"));
        skill.promoteBaseVersion(version.getId(), skillKey, "desc", "triggers");
        skills.save(skill);
        return skillKey;
    }

    private UUID newCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = om.readTree(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hire\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(created.get("id").asText());
    }
}
