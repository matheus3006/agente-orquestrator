package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.common.security.ActorType;
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

/**
 * A fila de Approvals + a decisão via HTTP (T.3, board-only): o grill responde 422 com o probe
 * (o commit NÃO aconteceu); o commit responde 200; o Log de Decisões e a carta ficam legíveis
 * pelo board (decisions filtrável + memory/carta).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ApprovalService approvals;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void filaGrillEDecisaoBoardOnly() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID ceoId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"role\":\"ceo\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // Semeia uma proposta de DIREÇÃO pelo serviço (o caminho por-Run já é coberto no gate test).
        UUID approvalId = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"title\":\"Focar no nicho dental\"}", "Focar no nicho dental",
                        "direcao-roadmap")).getId();

        String base = "/api/companies/" + companyId + "/approvals";

        // Agente com chave não vê a fila nem decide (board-only).
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + ceoId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(base).header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());

        // A fila PENDING do board.
        mvc.perform(get(base).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(approvalId.toString()))
                .andExpect(jsonPath("$[0].actionType").value("CREATE_ISSUE"))
                .andExpect(jsonPath("$[0].racionalTipo").value("LOGICO"));

        String decisionUrl = base + "/" + approvalId + "/decision";

        // Sem racional → 422 com o probe do grill (o commit NÃO aconteceu).
        mvc.perform(post(decisionUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.grilled").value(true))
                .andExpect(jsonPath("$.probe").exists())
                .andExpect(jsonPath("$.rounds").value(1));

        // Com racional → 200, APPROVED, racionalArrancado (houve grill antes).
        mvc.perform(post(decisionUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"nicho com dor aguda e ticket alto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.racionalArrancado").value(true));

        // Decidir de novo → 409.
        mvc.perform(post(decisionUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"racional\":\"tarde\"}"))
                .andExpect(status().isConflict());

        // O Log de Decisões filtrável (board-only) mostra a decisão do gate.
        mvc.perform(get("/api/companies/" + companyId + "/decisions?tipo=DIRECAO")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("DIRECAO"))
                .andExpect(jsonPath("$[0].racional").value("nicho com dor aguda e ticket alto"));
        mvc.perform(get("/api/companies/" + companyId + "/decisions").header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());

        // A carta consolidada aparece pro board.
        mvc.perform(get("/api/companies/" + companyId + "/memory/carta")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Focar no nicho dental")));
    }
}
