package com.orquestrador.work.review;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.work.issue.IssueService;
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

/**
 * Decisão de review via HTTP (ADR-0012): board-only (agente 403), cross-tenant 403,
 * APPROVE → 200/DONE, decidir Issue não-estacionada → 409. O estado PENDING_REVIEW é
 * montado pelo caminho real do gate (run real + settleAfterRun).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewDecisionHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private IssueService issues;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void decisaoBoardOnlyCrossTenantEGuard() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID agentId = createAgent(companyId, board);

        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"alto risco\",\"riskLevel\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // Estaciona pelo caminho real do gate (run real do motor + settle de sucesso).
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success());

        String reviewUrl = "/api/companies/" + companyId + "/issues/" + issueId + "/review";
        String approve = "{\"decision\":\"APPROVE\"}";

        // agente (com chave) NÃO decide (board-only) -> 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post(reviewUrl).header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isForbidden());

        // cross-tenant (outro board sem membership) -> 403
        mvc.perform(post(reviewUrl).header("X-Board-User", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isForbidden());

        // board aprova -> 200, DONE
        mvc.perform(post(reviewUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // decidir de novo (já DONE, não PENDING_REVIEW) -> 409
        mvc.perform(post(reviewUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"tarde\"}"))
                .andExpect(status().isConflict());

        // decision com typo (enum inválido) é erro do CLIENTE -> 400, nunca 500 (achado da revisão).
        mvc.perform(post(reviewUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APROVE\"}"))
                .andExpect(status().isBadRequest());

        // reason sem bound é payload-bomb na fila do board (achado da revisão) -> @Size, 422.
        mvc.perform(post(reviewUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"" + "x".repeat(3000) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * Contratar EXIGE time (ADR-0045). Aqui o agente é só quem entrega a Issue revisada, então ele
     * nasce no PRÓPRIO time e vira arquiteto dele: o dispatch segue destravado (ADR-0017) e a chefia
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
