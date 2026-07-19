package com.orquestrador.org.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Motor por agente via HTTP: create com motor expõe model/effort; PATCH promove; cross-tenant negado. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentEngineHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID newCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Foco aleatório porque (company, dominio, foco) é único — cada time nasce só para este teste. */
    private UUID newTeam(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** O arquiteto tem que ser MEMBRO, então isto só roda depois de o agente já estar no time. */
    private void designarArquiteto(UUID companyId, UUID board, UUID teamId, UUID agentId) throws Exception {
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Contratar EXIGE time (ADR-0045). Aqui o agente é só cenário, então ele nasce no PRÓPRIO time
     * e vira arquiteto dele: assim o dispatch segue destravado (ADR-0017) e a chefia derivada
     * continua nula — exatamente o mundo que este teste já assumia.
     */
    private UUID newAgent(UUID companyId, UUID board) throws Exception {
        UUID teamId = newTeam(companyId, board);
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\",\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        designarArquiteto(companyId, board, teamId, agentId);
        return agentId;
    }

    @Test
    void createComMotorExpoeModelEffort_semMotorFicamNull_patchPromove() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = newCompany(board);
        // Contratar EXIGE time (ADR-0045). O time aqui é só o endereço dos dois: eles dividem um só,
        // e Lyra vira arquiteto dele — dispatch destravado (ADR-0017), como antes da mudança.
        UUID teamId = newTeam(companyId, board);

        // create COM motor -> response expõe adapterType/model/effort
        String withEngine = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lyra\",\"role\":\"architect\",\"teamId\":\"" + teamId + "\","
                                + "\"adapterType\":\"claude-code\","
                                + "\"model\":\"opus\",\"effort\":\"ultracode\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("claude-code"))
                .andExpect(jsonPath("$.model").value("opus"))
                .andExpect(jsonPath("$.effort").value("ultracode"))
                .andReturn().getResponse().getContentAsString();
        UUID lyraId = idFrom(withEngine);
        designarArquiteto(companyId, board, teamId, lyraId);

        // create SEM motor -> model/effort null (ausente ou null), adapter default
        String noEngine = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Echo\",\"role\":\"analyst\",\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adapterType").value("process"))
                .andReturn().getResponse().getContentAsString();
        JsonNode noEngineNode = om.readTree(noEngine);
        assertThat(noEngineNode.path("model").isNull() || noEngineNode.path("model").isMissingNode()).isTrue();
        assertThat(noEngineNode.path("effort").isNull() || noEngineNode.path("effort").isMissingNode()).isTrue();

        // PATCH promove o motor inteiro (troca para GPT)
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + lyraId)
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adapterType\":\"codex\",\"model\":\"gpt-5.5\",\"effort\":\"xhigh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterType").value("codex"))
                .andExpect(jsonPath("$.model").value("gpt-5.5"))
                .andExpect(jsonPath("$.effort").value("xhigh"));
    }

    @Test
    void patchCrossTenantNaoEncontraOAgente() throws Exception {
        UUID boardA = UUID.randomUUID();
        UUID companyA = newCompany(boardA);
        UUID agentA = newAgent(companyA, boardA);

        // outra company (outro board) tentando promover o agente da company A -> 404 (não pertence)
        UUID boardB = UUID.randomUUID();
        UUID companyB = newCompany(boardB);
        mvc.perform(patch("/api/companies/" + companyB + "/agents/" + agentA)
                        .header("X-Board-User", boardB.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adapterType\":\"codex\",\"model\":\"gpt-5.5\",\"effort\":\"xhigh\"}"))
                .andExpect(status().isNotFound());
    }
}
