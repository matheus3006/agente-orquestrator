package com.orquestrador.org.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.7 fatia E (ADR-0014): GET /engines serve o catálogo CURADO de motores — só {@code enabled=true},
 * ordenado, board-only. O catálogo é GLOBAL (sem company_id); a rota é company-scoped só para gatear
 * acesso. H2 não tem o seed do V13 → o teste semeia via repo (nonce por-método isola do contexto).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EngineCatalogHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private EngineCatalogRepository catalog;

    @Test
    void serveSoHabilitadosOrdenadosPorLabel() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String nonce = "zz-" + UUID.randomUUID().toString().substring(0, 8);

        // ordem de inserção fora de ordem de label; um desabilitado (não deve sair)
        catalog.save(new EngineCatalog(UUID.randomUUID(), "codex", "gpt-5.5", "high", 256000, nonce + "-C-gpt", true));
        catalog.save(new EngineCatalog(UUID.randomUUID(), "claude-code", "opus", "ultracode", 200000, nonce + "-A-opus", true));
        catalog.save(new EngineCatalog(UUID.randomUUID(), "process", null, null, null, nonce + "-D-off", false));
        catalog.save(new EngineCatalog(UUID.randomUUID(), "claude-code", "sonnet", "high", 200000, nonce + "-B-sonnet", true));

        JsonNode engines = om.readTree(mvc.perform(
                        get("/api/companies/" + companyId + "/engines").header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        // filtra só as linhas deste teste (catálogo global compartilhado no contexto)
        List<String> labels = new java.util.ArrayList<>();
        JsonNode opusRow = null;
        for (JsonNode engine : engines) {
            String label = engine.get("label").asText();
            if (label.startsWith(nonce)) {
                labels.add(label);
                if (label.endsWith("-A-opus")) {
                    opusRow = engine;
                }
            }
        }
        // só os 3 habilitados, em ordem de label (A, B, C); o desabilitado (-D-off) não aparece
        assertThat(labels).containsExactly(nonce + "-A-opus", nonce + "-B-sonnet", nonce + "-C-gpt");
        // o trio do motor é servido para a UI preencher (adapterType + model + effort + contextWindow)
        assertThat(opusRow.get("adapterType").asText()).isEqualTo("claude-code");
        assertThat(opusRow.get("model").asText()).isEqualTo("opus");
        assertThat(opusRow.get("effort").asText()).isEqualTo("ultracode");
        assertThat(opusRow.get("contextWindow").asInt()).isEqualTo(200000);
    }

    @Test
    void superficieBoardOnlyAgente403EBoardSemMembership403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        String url = "/api/companies/" + companyId + "/engines";

        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        mvc.perform(get(url).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Engines\",\"slug\":\"" + slug + "\"}"))
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
