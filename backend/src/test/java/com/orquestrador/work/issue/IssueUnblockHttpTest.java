package com.orquestrador.work.issue;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Destrava de Issue via HTTP (ADR-0011): board-only (agente 403), cross-tenant 403, REOPEN→OPEN (200),
 * e destravar uma Issue não-BLOCKED → 409. O estado BLOCKED é montado pelo seam {@code settleAfterRun}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueUnblockHttpTest {

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
    void unblockBoardOnlyECrossTenant() throws Exception {
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
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"tarefa\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // Monta o estado BLOCKED pelo seam (um run REAL detém a Issue e falha) — o checkout exige
        // procedencia do runId (Fatia I-ter).
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.FAILED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.failure("boom"));

        String reopen = "{\"decision\":\"REOPEN\"}";
        String unblockUrl = "/api/companies/" + companyId + "/issues/" + issueId + "/unblock";

        // agente (com chave) NÃO destrava (board-only) -> 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(post(unblockUrl).header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isForbidden());

        // cross-tenant (outro board sem membership) -> 403
        mvc.perform(post(unblockUrl).header("X-Board-User", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isForbidden());

        // board destrava REOPEN -> 200, status OPEN
        mvc.perform(post(unblockUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // destravar de novo (já OPEN, não BLOCKED) -> 409
        mvc.perform(post(unblockUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(reopen))
                .andExpect(status().isConflict());
    }

    /**
     * Contratar EXIGE time (ADR-0045). Aqui o agente é só quem detém a Issue que falha, então ele
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
