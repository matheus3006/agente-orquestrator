package com.orquestrador.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SkillSet base por-agente via HTTP: selecionar (board 201), listar, e deny-by-default (agente e
 * cross-tenant negados). Import a partir de raiz-fixture via @DynamicPropertySource.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentSkillHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-agentskill-import");
            Path dir = Files.createDirectories(IMPORT_ROOT.resolve("planner"));
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: planner\ndescription: planeja\n---\n# Planner\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void importRoot(DynamicPropertyRegistry registry) {
        registry.add("orquestrador.skills.import-root", IMPORT_ROOT::toString);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void selecionaListaENegaAgenteECrossTenant() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // importa o catálogo da company e pega o companySkillId da 'planner'
        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk());
        String catalog = mvc.perform(get("/api/companies/" + companyId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode entry = om.readTree(catalog).get(0);
        String companySkillId = entry.get("companySkillId").asText();

        String body = "{\"companySkillId\":\"" + companySkillId + "\"}";

        // board seleciona -> 201
        mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/skills")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // lista mostra a skill selecionada
        mvc.perform(get("/api/companies/" + companyId + "/agents/" + agentId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].skillKey").value("planner"));

        // agente (com chave) NAO define o SkillSet -> 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/skills")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // cross-tenant (outro board, sem membership) -> 403
        mvc.perform(get("/api/companies/" + companyId + "/agents/" + agentId + "/skills")
                        .header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
