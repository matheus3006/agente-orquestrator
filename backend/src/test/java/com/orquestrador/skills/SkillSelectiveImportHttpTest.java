package com.orquestrador.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Import SELETIVO pelo edge HTTP (cenário F.10: raiz com várias skills, instalar UMA). Body opcional
 * {@code {"skillKeys": [...]}} restringe a varredura aos diretórios nomeados; sem body o comportamento
 * atual (raiz inteira) permanece. A seleção pedida fica na trilha de auditoria.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillSelectiveImportHttpTest {

    private static final Path IMPORT_ROOT;

    static {
        try {
            IMPORT_ROOT = Files.createTempDirectory("orq-skills-import-seletivo");
            for (String skillKey : List.of("sel-http-alvo", "sel-http-resto")) {
                Path dir = Files.createDirectories(IMPORT_ROOT.resolve(skillKey));
                Files.writeString(dir.resolve("SKILL.md"),
                        "---\nname: " + skillKey + "\ndescription: fixture\n---\n# " + skillKey + "\n");
            }
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

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return UUID.fromString(om.readTree(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());
    }

    @Test
    void importComBodyInstalaSoAsSkillsPedidasEAuditaASelecao() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);

        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillKeys\":[\"sel-http-alvo\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(0));

        mvc.perform(get("/api/companies/" + companyId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].skillKey").value("sel-http-alvo"));

        // a auditoria do import seletivo registra QUAIS chaves foram pedidas
        assertThat(activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .anyMatch(e -> e.getAction().equals("SKILL_IMPORTED")
                        && e.getDetail() != null && e.getDetail().contains("sel-http-alvo"));
    }

    @Test
    void importSemBodyContinuaImportandoARaizInteira() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);

        mvc.perform(post("/api/companies/" + companyId + "/skills/import")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2));

        mvc.perform(get("/api/companies/" + companyId + "/skills")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
