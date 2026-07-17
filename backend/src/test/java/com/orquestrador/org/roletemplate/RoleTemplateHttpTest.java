package com.orquestrador.org.roletemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Superfície do catálogo GLOBAL de moldes (ADR-0017/0018): board-only, company-scoped só para gatear
 * (clona {@link com.orquestrador.org.engine.EngineCatalogController}). Board vê/edita o catálogo global;
 * agente e board sem membership levam 403 (o catálogo global mexe em TODAS as empresas → gate por alcance).
 * H2 não tem o seed do V14 → nonce por-método isola do catálogo global compartilhado no contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleTemplateHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void boardCriaVersionaEListaOCatalogoGlobal() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String role = "arch-" + UUID.randomUUID().toString().substring(0, 8);
        String url = "/api/companies/" + companyId + "/role-templates";

        // POST cria molde global + v1 (board-only)
        String created = mvc.perform(post(url).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoFuncao\":\"PLANEJAMENTO_ARQUITETO\",\"roleEspecifica\":\"" + role + "\","
                                + "\"adapterType\":\"claude-code\",\"model\":\"opus\",\"effort\":\"ultracode\","
                                + "\"skillKeys\":[\"tdd\"],\"reportsToDefault\":\"LIDERANCA\","
                                + "\"changeDescription\":\"molde inicial\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipoFuncao").value("PLANEJAMENTO_ARQUITETO"))
                .andExpect(jsonPath("$.roleEspecifica").value(role))
                .andExpect(jsonPath("$.currentVersionId").exists())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(om.readTree(created).get("id").asText());

        // POST /versions anexa v2 (savelog)
        mvc.perform(post(url + "/" + templateId + "/versions").header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adapterType\":\"codex\",\"model\":\"gpt-5.5\",\"effort\":\"xhigh\","
                                + "\"skillKeys\":[],\"changeType\":\"manual\",\"changeDescription\":\"trocou motor\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        // GET lista o catálogo global; o molde criado está lá (filtra pelo nonce da role)
        JsonNode list = om.readTree(mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean found = false;
        for (JsonNode t : list) {
            if (role.equals(t.path("roleEspecifica").asText())) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void savelogDevolveVersoesDescComSkillsetEReportsTo() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String role = "arch-" + UUID.randomUUID().toString().substring(0, 8);
        String url = "/api/companies/" + companyId + "/role-templates";

        String created = mvc.perform(post(url).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoFuncao\":\"PLANEJAMENTO_ARQUITETO\",\"roleEspecifica\":\"" + role + "\","
                                + "\"adapterType\":\"claude-code\",\"model\":\"opus\",\"effort\":\"ultracode\","
                                + "\"skillKeys\":[\"tdd\",\"codebase-design\"],\"reportsToDefault\":\"LIDERANCA\","
                                + "\"changeDescription\":\"v1\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(om.readTree(created).get("id").asText());

        // O POST /versions passou a devolver o preset por inteiro (skillKeys + reportsToDefault)
        mvc.perform(post(url + "/" + templateId + "/versions").header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adapterType\":\"codex\",\"model\":\"gpt-5.5\",\"effort\":\"xhigh\","
                                + "\"skillKeys\":[\"diagnose\"],\"reportsToDefault\":\"GESTAO_PO\","
                                + "\"changeType\":\"manual\",\"changeDescription\":\"trocou motor\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skillKeys[0]").value("diagnose"))
                .andExpect(jsonPath("$.reportsToDefault").value("GESTAO_PO"));

        // GET /versions: savelog completo, desc por version, cada versão com o preset LEGÍVEL
        // (skillset jsonb já desserializado em skillKeys) — o que a biblioteca (T.1-FE) consome.
        mvc.perform(get(url + "/" + templateId + "/versions").header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].roleTemplateId").value(templateId.toString()))
                .andExpect(jsonPath("$[0].skillKeys.length()").value(1))
                .andExpect(jsonPath("$[0].skillKeys[0]").value("diagnose"))
                .andExpect(jsonPath("$[0].reportsToDefault").value("GESTAO_PO"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].skillKeys.length()").value(2))
                .andExpect(jsonPath("$[1].skillKeys[0]").value("tdd"))
                .andExpect(jsonPath("$[1].skillKeys[1]").value("codebase-design"))
                .andExpect(jsonPath("$[1].reportsToDefault").value("LIDERANCA"));

        // molde inexistente → 404 (board autenticado; o guard passa, o savelog não acha)
        mvc.perform(get(url + "/" + UUID.randomUUID() + "/versions").header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void agenteEBoardSemMembershipLevam403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        String url = "/api/companies/" + companyId + "/role-templates";

        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        // agente autenticado → 403 (catálogo global é material do operador)
        mvc.perform(get(url).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipoFuncao\":\"EXECUCAO\",\"roleEspecifica\":\"x\"}"))
                .andExpect(status().isForbidden());
        // savelog idem — e o guard corre ANTES do lookup (403, nunca 404 vazando existência)
        mvc.perform(get(url + "/" + UUID.randomUUID() + "/versions")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
        // board sem membership na company → 403
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Roles\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
