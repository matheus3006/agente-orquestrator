package com.orquestrador.work.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R.4 (ADR-0012): a fila de revisão do board — GET /reviews?status=PENDING, board-only
 * (agente 403, cross-tenant 403), ordenada por requested_at (chegada). Default = PENDING.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewQueueHttpTest {

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
    void filaBoardOnlyListaPendentesEmOrdemDeChegada() throws Exception {
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

        UUID primeiraIssue = parkForReview(companyId, agentId, board);
        Thread.sleep(5); // separa os requested_at (ordem de chegada)
        UUID segundaIssue = parkForReview(companyId, agentId, board);

        String queueUrl = "/api/companies/" + companyId + "/reviews";

        // board lista a fila (default PENDING), em ordem de chegada, com o essencial p/ decidir
        JsonNode queue = om.readTree(mvc.perform(get(queueUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).get("issueId").asText()).isEqualTo(primeiraIssue.toString());
        assertThat(queue.get(1).get("issueId").asText()).isEqualTo(segundaIssue.toString());
        assertThat(queue.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(queue.get(0).get("runId").asText()).isNotEmpty();

        // decidida some da fila PENDING e aparece filtrando por APPROVED
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + primeiraIssue + "/review")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        JsonNode pendentes = om.readTree(mvc.perform(get(queueUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pendentes).hasSize(1);
        JsonNode aprovadas = om.readTree(mvc.perform(get(queueUrl + "?status=APPROVED")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aprovadas).hasSize(1);

        // agente (com chave) NÃO vê a fila (board-only) -> 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(queueUrl).header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());

        // cross-tenant (outro board sem membership) -> 403
        mvc.perform(get(queueUrl).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        // status inválido no filtro é erro do CLIENTE -> 400, nunca 500 (achado da revisão).
        mvc.perform(get(queueUrl + "?status=NOPE").header("X-Board-User", board.toString()))
                .andExpect(status().isBadRequest());
    }

    private UUID parkForReview(UUID companyId, UUID agentId, UUID board) throws Exception {
        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"alto risco\",\"riskLevel\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success());
        return issueId;
    }
}
