package com.orquestrador.work.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fecha o bypass ALTA da revisão adversarial da R.2: o agente (Bearer api-key) que conclui via
 * POST /complete uma Issue de alto risco NÃO leva a Issue a DONE — o mesmo funil do gate estaciona
 * em PENDING_REVIEW e abre o ciclo de review. "O agente pulou a revisão" não existe nem via HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueCompleteGateHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void completeHttpDeAltoRiscoEstacionaEmPendingReview() throws Exception {
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

        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"alto risco\",\"riskLevel\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        // Agente com run REAL do motor tenta concluir direto — o cenário do bypass.
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/checkout")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/complete")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW")); // NÃO DONE

        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId))
                .hasSize(1)
                .allSatisfy(review -> assertThat(review.getStatus()).isEqualTo(ReviewStatus.PENDING));

        // Checkout SEM runId (o caminho manual da fatia 1 gera um sintético, sem linha em
        // heartbeat_run): concluir alto risco exige um run real revisável → 409, nada muda.
        UUID secondIssue = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"alto risco manual\",\"riskLevel\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String manualCheckout = mvc.perform(post("/api/companies/" + companyId + "/issues/" + secondIssue + "/checkout")
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String syntheticRun = om.readTree(manualCheckout).get("runId").asText();
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + secondIssue + "/complete")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + syntheticRun + "\"}"))
                .andExpect(status().isConflict());
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, secondIssue)).isEmpty();
    }
}
