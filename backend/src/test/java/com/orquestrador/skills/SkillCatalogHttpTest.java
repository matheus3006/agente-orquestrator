package com.orquestrador.skills;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityLogRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edge HTTP do catalogo: import a partir da raiz CONFIGURADA, listagem e deny-by-default
 * (cross-tenant -> 403). A raiz aponta para um fixture temporario via @DynamicPropertySource.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillCatalogHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-skills-import");
            Path dir = Files.createDirectories(IMPORT_ROOT.resolve("tdd"));
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: tdd\ndescription: TDD helper\n---\n# TDD\n");
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
    @Autowired
    private ActivityLogRepository activityLog;

    @Test
    void importaListaENegaCrossTenant() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = UUID.fromString(om.readTree(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());

        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // import do operador é auditado (activity log)
        assertThat(activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .anyMatch(e -> e.getAction().equals("SKILL_IMPORTED"));

        mvc.perform(get("/api/companies/" + companyId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].skillKey").value("tdd"));

        // outro board user, sem membership -> deny-by-default
        mvc.perform(get("/api/companies/" + companyId + "/skills")
                        .header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
