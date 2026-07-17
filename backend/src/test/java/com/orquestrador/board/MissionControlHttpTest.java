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
 * F.0 (frontend fechado, Q3): GET /mission-control — o agregado que alimenta a home da company.
 * O SERVIDOR faz o join (1 poll = a tela inteira): por agente → status derivado
 * (RUNNING/QUEUED/BLOCKED/IDLE), run ativo (com título da Issue) e os últimos ≤5 steps do
 * StepTrace (do run ativo, ou do run mais recente quando ocioso). Board-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissionControlHttpTest {

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
    void cardsDerivamStatusRunAtivoEUltimosSteps() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID runnerId = createAgent(companyId, board, "runner");
        UUID ociosoId = createAgent(companyId, board, "ocioso");
        UUID bloqueadoId = createAgent(companyId, board, "bloqueado");
        UUID veteranoId = createAgent(companyId, board, "veterano");

        // runner: run RUNNING numa Issue, com 7 linhas de stdout no StepTrace
        UUID issueRodando = createIssue(companyId, board, "Refatorar modulo de pagamentos");
        UUID runRodando = queueRun(companyId, runnerId, issueRodando);
        store.claim(runRodando, "worker-test", Duration.ofMinutes(5));
        issues.checkout(companyId, issueRodando, runnerId, runRodando);
        for (int i = 1; i <= 7; i++) {
            Thread.sleep(2); // separa os created_at (ordem estável do trace)
            store.recordStep(runRodando, "STDOUT", "linha-" + i);
        }

        // bloqueado: run FAILED + settle → Issue BLOCKED (park-for-human, ADR-0011)
        UUID issueBloqueada = createIssue(companyId, board, "Deploy arriscado");
        UUID runFalho = queueRun(companyId, bloqueadoId, issueBloqueada);
        store.claim(runFalho, "worker-test", Duration.ofMinutes(5));
        issues.checkout(companyId, issueBloqueada, bloqueadoId, runFalho);
        Thread.sleep(2);
        store.recordStep(runFalho, "STDOUT", "erro fatal no passo 3");
        store.finishFailure(runFalho, "worker-test", "exit 1");
        issues.settleAfterRun(companyId, issueBloqueada, runFalho, RunOutcome.failure("exit 1"));

        // veterano: run antigo SUCCEEDED, sem nada ativo → IDLE mas o card mantém o rastro
        UUID runAntigo = queueRun(companyId, veteranoId, null);
        store.claim(runAntigo, "worker-test", Duration.ofMinutes(5));
        store.finishSuccess(runAntigo, "worker-test");

        JsonNode snapshot = om.readTree(mvc.perform(
                        get("/api/companies/" + companyId + "/mission-control")
                                .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode cards = snapshot.get("agents");
        assertThat(cards).hasSize(4);
        // ordem estável (roster por criação): cards não dançam entre polls
        assertThat(cards.get(0).get("agent").get("id").asText()).isEqualTo(runnerId.toString());
        assertThat(cards.get(1).get("agent").get("id").asText()).isEqualTo(ociosoId.toString());
        assertThat(cards.get(2).get("agent").get("id").asText()).isEqualTo(bloqueadoId.toString());
        assertThat(cards.get(3).get("agent").get("id").asText()).isEqualTo(veteranoId.toString());

        JsonNode runner = cards.get(0);
        assertThat(runner.get("agent").get("name").asText()).isEqualTo("runner");
        assertThat(runner.get("agent").get("role").asText()).isEqualTo("worker");
        assertThat(runner.get("status").asText()).isEqualTo("RUNNING");
        assertThat(runner.get("activeRun").get("id").asText()).isEqualTo(runRodando.toString());
        assertThat(runner.get("activeRun").get("issueId").asText()).isEqualTo(issueRodando.toString());
        assertThat(runner.get("activeRun").get("issueTitle").asText())
                .isEqualTo("Refatorar modulo de pagamentos");
        // #N no card (ADR-0033): "executando #1 Refatorar..." — issueRodando e a 1a issue da company
        assertThat(runner.get("activeRun").get("issueSeq").asInt()).isEqualTo(1);
        assertThat(runner.get("activeRun").get("attempt").asInt()).isEqualTo(1);
        assertThat(runner.get("activeRun").get("startedAt").asText()).isNotEmpty();
        // mini-terminal: os ÚLTIMOS 5 (de RUN_STARTED + 7 linhas), em ordem cronológica
        JsonNode trace = runner.get("lastSteps");
        assertThat(trace).hasSize(5);
        assertThat(trace.get(0).get("detail").asText()).isEqualTo("linha-3");
        assertThat(trace.get(4).get("detail").asText()).isEqualTo("linha-7");
        assertThat(trace.get(0).get("runId").asText()).isEqualTo(runRodando.toString());
        assertThat(trace.get(0).get("type").asText()).isEqualTo("STDOUT");
        assertThat(trace.get(0).get("createdAt").asText()).isNotEmpty();

        JsonNode ocioso = cards.get(1);
        assertThat(ocioso.get("status").asText()).isEqualTo("IDLE");
        assertThat(ocioso.get("activeRun").isNull()).isTrue();
        assertThat(ocioso.get("lastSteps")).isEmpty();

        JsonNode bloqueado = cards.get(2);
        assertThat(bloqueado.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(bloqueado.get("activeRun").isNull()).isTrue();
        // o rastro da falha continua visível no card (RUN_STARTED, stdout, RUN_FAILED)
        assertThat(bloqueado.get("lastSteps")).hasSize(3);
        assertThat(bloqueado.get("lastSteps").get(1).get("detail").asText())
                .isEqualTo("erro fatal no passo 3");
        assertThat(bloqueado.get("lastSteps").get(2).get("type").asText()).isEqualTo("RUN_FAILED");

        JsonNode veterano = cards.get(3);
        assertThat(veterano.get("status").asText()).isEqualTo("IDLE");
        assertThat(veterano.get("activeRun").isNull()).isTrue();
        assertThat(veterano.get("lastSteps")).hasSize(2);
    }

    @Test
    void runNaFilaApareceQueuedERunningVenceIssueBloqueadaAntiga() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board, "ressuscitado");

        // primeiro run falha → Issue BLOCKED atribuída ao agente
        UUID issueBloqueada = createIssue(companyId, board, "Primeira tentativa");
        UUID runFalho = queueRun(companyId, agentId, issueBloqueada);
        store.claim(runFalho, "worker-test", Duration.ofMinutes(5));
        issues.checkout(companyId, issueBloqueada, agentId, runFalho);
        store.finishFailure(runFalho, "worker-test", "exit 1");
        issues.settleAfterRun(companyId, issueBloqueada, runFalho, RunOutcome.failure("exit 1"));

        // run novo QUEUED (ainda sem claim): trabalho na fila NÃO é ocioso — e vence o BLOCKED antigo
        UUID issueNova = createIssue(companyId, board, "Segunda tentativa");
        UUID runNaFila = queueRun(companyId, agentId, issueNova);

        JsonNode card = card(companyId, board, 0);
        assertThat(card.get("status").asText()).isEqualTo("QUEUED");
        assertThat(card.get("activeRun").get("id").asText()).isEqualTo(runNaFila.toString());
        assertThat(card.get("activeRun").get("startedAt").isNull()).isTrue();

        // claim → RUNNING (precedência sobre a Issue BLOCKED que continua atribuída a ele)
        store.claim(runNaFila, "worker-test", Duration.ofMinutes(5));
        card = card(companyId, board, 0);
        assertThat(card.get("status").asText()).isEqualTo("RUNNING");
    }

    @Test
    void agregadoEhBoardOnly() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board, "agente");
        String url = "/api/companies/" + companyId + "/mission-control";

        // agente autenticado (Bearer) NÃO vê a superfície do operador → 403
        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(url).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());

        // board sem membership → 403 (deny-by-default)
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private JsonNode card(UUID companyId, UUID board, int index) throws Exception {
        return om.readTree(mvc.perform(get("/api/companies/" + companyId + "/mission-control")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("agents").get(index);
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MC\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board, String name) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"worker\"}"))
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
