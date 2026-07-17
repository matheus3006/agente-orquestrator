package com.orquestrador.board;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.0 (frontend fechado, Q3): GET /issues/{issueId}/runs — o ledger de execuções do Issue
 * detail (toda tentativa, com desfecho e erro), mais recente primeiro. Antes só existia
 * runs-por-agente; esta é a leitura company-led por Issue. Board-only, Issue company-led (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueRunLedgerHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private IssueService issues;

    @Test
    void ledgerListaTodasAsTentativasDaIssueMaisRecentePrimeiro() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID issueId = createIssue(companyId, board, "Issue com historia");

        // 1ª tentativa: falha (run FAILED, Issue BLOCKED) — o ledger conta a história completa
        UUID runFalho = queueRun(companyId, agentId, issueId);
        store.claim(runFalho, "worker-test", Duration.ofMinutes(5));
        issues.checkout(companyId, issueId, agentId, runFalho);
        store.finishFailure(runFalho, "worker-test", "exit 1: script quebrou");
        issues.settleAfterRun(companyId, issueId, runFalho, RunOutcome.failure("exit 1: script quebrou"));

        // board destrava (REOPEN) e o agente tenta de novo — 2ª tentativa em voo
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/unblock")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REOPEN\"}"))
                .andExpect(status().isOk());
        Thread.sleep(2); // separa os created_at (ordem do ledger)
        UUID runAtual = queueRun(companyId, agentId, issueId);
        store.claim(runAtual, "worker-test", Duration.ofMinutes(5));
        issues.checkout(companyId, issueId, agentId, runAtual);

        JsonNode ledger = om.readTree(mvc.perform(
                        get("/api/companies/" + companyId + "/issues/" + issueId + "/runs")
                                .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(ledger).hasSize(2);
        JsonNode atual = ledger.get(0);
        assertThat(atual.get("id").asText()).isEqualTo(runAtual.toString());
        assertThat(atual.get("agentId").asText()).isEqualTo(agentId.toString());
        assertThat(atual.get("issueId").asText()).isEqualTo(issueId.toString());
        assertThat(atual.get("status").asText()).isEqualTo("RUNNING");
        assertThat(atual.get("attempt").asInt()).isEqualTo(1);
        assertThat(atual.get("startedAt").asText()).isNotEmpty();
        assertThat(atual.get("finishedAt").isNull()).isTrue();
        assertThat(atual.get("lastError").isNull()).isTrue();

        JsonNode falho = ledger.get(1);
        assertThat(falho.get("id").asText()).isEqualTo(runFalho.toString());
        assertThat(falho.get("status").asText()).isEqualTo("FAILED");
        assertThat(falho.get("finishedAt").asText()).isNotEmpty();
        assertThat(falho.get("lastError").asText()).isEqualTo("exit 1: script quebrou");
    }

    @Test
    void issueSemRunsDaVazioForaDaCompany404EBoardOnly403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID issueId = createIssue(companyId, board, "Sem execucoes ainda");
        String url = "/api/companies/" + companyId + "/issues/" + issueId + "/runs";

        JsonNode vazio = om.readTree(mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(vazio).isEmpty();

        // Issue de OUTRA company não existe deste ponto de vista (company-led, não vaza existência)
        UUID outroBoard = UUID.randomUUID();
        UUID outraCompany = createCompany(outroBoard);
        mvc.perform(get("/api/companies/" + outraCompany + "/issues/" + issueId + "/runs")
                        .header("X-Board-User", outroBoard.toString()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/companies/" + companyId + "/issues/" + UUID.randomUUID() + "/runs")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());

        // superfície do operador: agente 403, board sem membership 403
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
                        .content("{\"name\":\"Ledger\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createIssue(UUID companyId, UUID board, String title) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID queueRun(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.QUEUED)).getId();
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
