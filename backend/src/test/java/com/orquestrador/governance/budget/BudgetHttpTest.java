package com.orquestrador.governance.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fluxo de budget via HTTP: cria policy, reporta custo que estoura, ve o incidente, faz override. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BudgetHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void cicloDeBudgetViaHttp() throws Exception {
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

        // policy AGENT limite 100
        mvc.perform(post("/api/companies/" + companyId + "/budget-policies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"AGENT\",\"scopeId\":\"" + agentId + "\",\"limitCents\":100}"))
                .andExpect(status().isCreated());

        // custo que estoura (150 >= 100)
        mvc.perform(post("/api/companies/" + companyId + "/cost-events")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"costCents\":150}"))
                .andExpect(status().isAccepted());

        // incidente OPEN aparece
        String incidents = mvc.perform(get("/api/companies/" + companyId + "/budget-incidents")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].scopeType").value("AGENT"))
                .andReturn().getResponse().getContentAsString();
        UUID incidentId = UUID.fromString(om.readTree(incidents).get(0).get("id").asText());

        // override
        mvc.perform(post("/api/companies/" + companyId + "/budget-incidents/" + incidentId + "/resolve")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/companies/" + companyId + "/budget-incidents")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RESOLVED"));
    }

    @Test
    void policyComLimiteNaoPositivoRetorna422() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // limite 0 é degenerado (spent >= 0 é sempre verdadeiro) → a borda recusa com 422
        mvc.perform(post("/api/companies/" + companyId + "/budget-policies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"COMPANY\",\"limitCents\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
