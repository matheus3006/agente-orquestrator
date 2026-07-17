package com.orquestrador.board;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-N4 (ADR-0040, D4): POST /runs/{runId}/interrupt — o botao "parar" do board. Molde de
 * {@link RunStepsHttpTest} (mesma company-led + board-only). O CAS RUNNING->INTERRUPTED e
 * provado em {@code RunInterruptTest}/{@code RunInterruptServiceTest}; aqui e so a fiacao HTTP:
 * status code certo por cenario + guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunInterruptHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunStore store;

    @Test
    void interromperRunEmExecucaoRetorna200EMarcaInterrompido() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));

        JsonNode resposta = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/runs/" + runId + "/interrupt")
                                .header("X-Board-User", board.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"dono pediu parar\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(resposta.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(resposta.get("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.INTERRUPTED);
    }

    @Test
    void interromperRunQueNaoEstaRodandoDa409() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId(); // nunca claimado

        mvc.perform(post("/api/companies/" + companyId + "/runs/" + runId + "/interrupt")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isConflict());
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void runForaDaCompanyOuInexistenteDa404EBoardOnly403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));

        UUID outroBoard = UUID.randomUUID();
        UUID outraCompany = createCompany(outroBoard);
        mvc.perform(post("/api/companies/" + outraCompany + "/runs/" + runId + "/interrupt")
                        .header("X-Board-User", outroBoard.toString()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/companies/" + companyId + "/runs/" + UUID.randomUUID() + "/interrupt")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());

        // superficie do operador: agente autenticado nao para o proprio run (nem o de ninguem)
        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post("/api/companies/" + companyId + "/runs/" + runId + "/interrupt")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/companies/" + companyId + "/runs/" + runId + "/interrupt")
                        .header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        // nada dos 404/403 acima tocou o run: ainda RUNNING
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Interrupt\",\"slug\":\"" + slug + "\"}"))
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
