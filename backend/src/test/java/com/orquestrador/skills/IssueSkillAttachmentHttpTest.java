package com.orquestrador.skills;

import tools.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anexo por-Issue via HTTP (Fatia 7a): board anexa (201) e lista; deny-by-default — agente com chave é
 * board-only-negado (403), cross-tenant negado (403); anexar skill não instalada → 404 (só o instalado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueSkillAttachmentHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-issueskill-import");
            Path dir = Files.createDirectories(IMPORT_ROOT.resolve("reviewer"));
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: reviewer\ndescription: revisa\n---\n# Reviewer\n");
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
    void boardAnexaEDenyByDefault() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID agentId = createAgent(companyId, board);

        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk());
        // Fluxo real 7c: descobre a identidade GLOBAL (skillId) pelo KnowAll, e anexa por ela.
        String hits = mvc.perform(get("/api/companies/" + companyId + "/knowall/search")
                        .param("q", "reviewer").header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String skillId = om.readTree(hits).get(0).get("skillId").asText();

        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"tarefa\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String attachBody = "{\"skillId\":\"" + skillId + "\",\"agentId\":\"" + agentId + "\"}";

        // board anexa -> 201
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/skills")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").value(agentId.toString()));

        // lista mostra o anexo
        mvc.perform(get("/api/companies/" + companyId + "/issues/" + issueId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // agente (com chave) NAO anexa nesta fatia (board-only) -> 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/skills")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody))
                .andExpect(status().isForbidden());

        // cross-tenant (outro board, sem membership) -> 403
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/skills")
                        .header("X-Board-User", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(attachBody))
                .andExpect(status().isForbidden());

        // anexar skill GLOBAL inexistente -> 404 (installFromCatalog valida a identidade)
        String ghostBody = "{\"skillId\":\"" + UUID.randomUUID() + "\",\"agentId\":\"" + agentId + "\"}";
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/skills")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(ghostBody))
                .andExpect(status().isNotFound());
    }

    /**
     * Contratar EXIGE time (ADR-0045). Aqui o agente é só o destinatário do anexo, então ele nasce
     * no PRÓPRIO time e vira arquiteto dele: o dispatch segue destravado (ADR-0017) e a chefia
     * derivada continua nula — exatamente o mundo que este teste já assumia.
     */
    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        UUID teamId = createTeam(companyId, board);
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\",\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
        return agentId;
    }

    /** Foco aleatório porque (company, dominio, foco) é único e cada agente ganha o seu time. */
    private UUID createTeam(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
