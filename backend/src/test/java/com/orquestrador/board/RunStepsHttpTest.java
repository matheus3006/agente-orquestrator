package com.orquestrador.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.execution.RunStatus;
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
 * F.0 (frontend fechado, Q3): GET /runs/{runId}/steps?offset= — o transcript INCREMENTAL.
 * heartbeat_run_event é append-only, então offset por contagem é estável: o poll de 2s pede
 * só o delta (offset = nextOffset anterior). Devolve também o runStatus para o front saber
 * quando parar de pollar sem depender de outro endpoint. Board-only, run company-led (404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunStepsHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunStore store;

    @Test
    void transcriptIncrementalPorOffsetComLimitClampado() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5)); // grava RUN_STARTED
        for (int i = 1; i <= 7; i++) {
            Thread.sleep(2); // separa os created_at (ordem estável)
            store.recordStep(runId, "STDOUT", "linha-" + i);
        }
        String url = "/api/companies/" + companyId + "/runs/" + runId + "/steps";

        // primeira página: RUN_STARTED + linha-1..4, nextOffset aponta a continuação
        JsonNode primeira = getSteps(url + "?offset=0&limit=5", board);
        assertThat(primeira.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(primeira.get("runStatus").asText()).isEqualTo("RUNNING");
        assertThat(primeira.get("steps")).hasSize(5);
        assertThat(primeira.get("steps").get(0).get("type").asText()).isEqualTo("RUN_STARTED");
        assertThat(primeira.get("steps").get(1).get("detail").asText()).isEqualTo("linha-1");
        assertThat(primeira.get("steps").get(4).get("detail").asText()).isEqualTo("linha-4");
        assertThat(primeira.get("steps").get(4).get("createdAt").asText()).isNotEmpty();
        assertThat(primeira.get("nextOffset").asInt()).isEqualTo(5);

        // delta: só o que veio depois do offset (limit default cobre o resto)
        JsonNode delta = getSteps(url + "?offset=5", board);
        assertThat(delta.get("steps")).hasSize(3);
        assertThat(delta.get("steps").get(0).get("detail").asText()).isEqualTo("linha-5");
        assertThat(delta.get("steps").get(2).get("detail").asText()).isEqualTo("linha-7");
        assertThat(delta.get("nextOffset").asInt()).isEqualTo(8);

        // poll sem novidade: vazio, nextOffset estaciona (o front repete o mesmo offset)
        JsonNode vazio = getSteps(url + "?offset=8", board);
        assertThat(vazio.get("steps")).isEmpty();
        assertThat(vazio.get("nextOffset").asInt()).isEqualTo(8);

        // offset negativo e limit fora da faixa são clampados, nunca 500
        JsonNode clampado = getSteps(url + "?offset=-3&limit=2", board);
        assertThat(clampado.get("steps")).hasSize(2);
        assertThat(clampado.get("steps").get(0).get("type").asText()).isEqualTo("RUN_STARTED");
        assertThat(clampado.get("nextOffset").asInt()).isEqualTo(2);
        JsonNode minimo = getSteps(url + "?offset=0&limit=0", board);
        assertThat(minimo.get("steps")).hasSize(1);
    }

    @Test
    void runForaDaCompanyOuInexistenteDa404EBoardOnly403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();

        // run de OUTRA company não existe deste ponto de vista (company-led, não vaza existência)
        UUID outroBoard = UUID.randomUUID();
        UUID outraCompany = createCompany(outroBoard);
        mvc.perform(get("/api/companies/" + outraCompany + "/runs/" + runId + "/steps")
                        .header("X-Board-User", outroBoard.toString()))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/companies/" + companyId + "/runs/" + UUID.randomUUID() + "/steps")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());

        // StepTrace é stdout cru: superfície do operador, agente não lê → 403
        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get("/api/companies/" + companyId + "/runs/" + runId + "/steps")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/companies/" + companyId + "/runs/" + runId + "/steps")
                        .header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private JsonNode getSteps(String url, UUID board) throws Exception {
        return om.readTree(mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Steps\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
