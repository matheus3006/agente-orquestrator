package com.orquestrador.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O report do Ledger via HTTP (board-only): corpo do card de approval/disclosure na thread. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueLedgerHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void reportBoardOnlyE404SemRunConcluido() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = UUID.fromString(om.readTree(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andReturn().getResponse().getContentAsString()).get("id").asText());

        Agent worker = new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null);
        worker.configureRuntime("process", "printf 'RESULTADO: feito\\n'", "LOCAL");
        UUID workerId = agents.save(worker).getId();
        UUID issueId = issues.create(companyId, "Landing", null).getId();

        String url = "/api/companies/" + companyId + "/issues/" + issueId + "/report";

        // Sem run concluído → 404 (o Ledger não inventa).
        mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());

        wakeups.enqueue(companyId, workerId, issueId, "trabalhar");
        dispatcher.dispatchOnce();

        mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report").value("RESULTADO: feito"))
                .andExpect(jsonPath("$.runId").exists());

        // Outro board sem membership → 403.
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }
}
