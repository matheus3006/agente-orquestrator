package com.orquestrador.work.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout atomico ponta-a-ponta sobre o stack HTTP real, incluindo auth de agente por Bearer
 * (chave mintada -> hash -> ActorResolutionFilter) e o isolamento cross-tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueCheckoutHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID createCompany(UUID boardUser, String slug) throws Exception {
        String body = mvc.perform(post("/api/companies")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Produto\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    private UUID createAgent(UUID boardUser, UUID companyId, String name) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    private String mintKey(UUID boardUser, UUID companyId, UUID agentId) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", boardUser.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("apiKey").asText();
    }

    private UUID createIssue(UUID boardUser, UUID companyId, String title) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    @Test
    void checkoutAtomicoEGovernancaViaHttp() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board, "co-" + rand());
        UUID a1 = createAgent(board, companyId, "A1");
        UUID a2 = createAgent(board, companyId, "A2");
        String keyA1 = mintKey(board, companyId, a1);
        String keyA2 = mintKey(board, companyId, a2);
        UUID issueId = createIssue(board, companyId, "tarefa");

        String checkoutUrl = "/api/companies/" + companyId + "/issues/" + issueId + "/checkout";

        // A1 pega -> 200, IN_PROGRESS, assignee A1
        String body = mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.issue.assigneeAgentId").value(a1.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(om.readTree(body).get("runId").asText());

        // A2 tenta -> 409 (zero double-work)
        mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA2))
                .andExpect(status().isConflict());

        // board nao faz checkout (so agentes) -> 403
        mvc.perform(post(checkoutUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isForbidden());

        // agente de OUTRA company -> 403 (cross-tenant)
        UUID board2 = UUID.randomUUID();
        UUID company2 = createCompany(board2, "co2-" + rand());
        UUID a3 = createAgent(board2, company2, "A3");
        String keyA3 = mintKey(board2, company2, a3);
        mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA3))
                .andExpect(status().isForbidden());

        // A1 conclui com o runId detentor -> 200 DONE
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }
}
