package com.orquestrador.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KnowAll match via HTTP (T.1.b): casa conhecimentos→skills e sinaliza gaps. Member-access (board E agente:
 * o CEO-agente de T.3 chama como tool, paridade com o /search); cross-tenant negado (403). Contexto próprio
 * (import-root fixture) → catálogo global isolado dos demais testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowAllMatchHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-knowall-match-import");
            Path dir = Files.createDirectories(IMPORT_ROOT.resolve("matchprobe"));
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: matchprobe\ndescription: sonda de casamento do knowall\n---\n# Probe\n");
        } catch (IOException creationFailure) {
            throw new UncheckedIOException(creationFailure);
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
    void casaConhecimentosSinalizaGap_agenteTambem_crossTenant403() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        String miss = "inexistente-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // instala a sonda na company (catálogo global + Camada 2)
        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk());

        String body = "{\"knowledge\":[\"matchprobe\",\"" + miss + "\"]}";

        // board casa -> 200: 1º conhecimento casa a sonda (INSTALLED), 2º vira gap
        mvc.perform(post("/api/companies/" + companyId + "/knowall/match")
                        .header("X-Board-User", board.toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].knowledge").value("matchprobe"))
                .andExpect(jsonPath("$.matches[0].matched").value(true))
                .andExpect(jsonPath("$.matches[0].skillKey").value("matchprobe"))
                .andExpect(jsonPath("$.matches[0].installState").value("INSTALLED"))
                .andExpect(jsonPath("$.matches[1].matched").value(false))
                .andExpect(jsonPath("$.gaps[0]").value(miss))
                .andExpect(jsonPath("$.matchedSkillKeys[0]").value("matchprobe"));

        // agente (com chave) TAMBÉM casa (KnowAll é tool do agente) -> 200
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post("/api/companies/" + companyId + "/knowall/match")
                        .header("Authorization", "Bearer " + key)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].skillKey").value("matchprobe"));

        // cross-tenant (outro board sem membership) -> 403
        mvc.perform(post("/api/companies/" + companyId + "/knowall/match")
                        .header("X-Board-User", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }
}
