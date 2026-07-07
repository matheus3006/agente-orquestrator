package com.orquestrador.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KnowAll search via HTTP (Fatia 7b): board e AGENTE (persona + tool) buscam (200); cross-tenant negado
 * (403). Contexto próprio (import-root fixture) → catálogo global isolado dos demais testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowAllSearchHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-knowall-import");
            Path dir = Files.createDirectories(IMPORT_ROOT.resolve("knowallprobe"));
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: knowallprobe\ndescription: sonda de busca do knowall\n---\n# Probe\n");
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
    void boardEAgenteBuscamCrossTenantNegado() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

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

        // board busca -> 200, acha a sonda anotada como INSTALLED
        mvc.perform(get("/api/companies/" + companyId + "/knowall/search")
                        .param("q", "knowall").header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillKey").value("knowallprobe"))
                .andExpect(jsonPath("$[0].installState").value("INSTALLED"));

        // agente (com chave) TAMBÉM busca (KnowAll é tool do agente) -> 200
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get("/api/companies/" + companyId + "/knowall/search")
                        .param("q", "knowall").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillKey").value("knowallprobe"));

        // termo sem casamento -> lista vazia (200)
        mvc.perform(get("/api/companies/" + companyId + "/knowall/search")
                        .param("q", "inexistente-zzz").header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // cross-tenant (outro board sem membership) -> 403
        mvc.perform(get("/api/companies/" + companyId + "/knowall/search")
                        .param("q", "knowall").header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
