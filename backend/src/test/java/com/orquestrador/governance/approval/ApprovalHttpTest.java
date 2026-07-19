package com.orquestrador.governance.approval;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.common.security.ActorType;
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
    @Autowired
    private com.orquestrador.org.team.TeamRepository teams;
    @Autowired
    private com.orquestrador.org.agent.AgentRepository agents;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    /**
     * Contratar exige time (ADR-0045): o CEO nasce num time de direção próprio e é o arquiteto
     * dele — sem arquiteto a trava do dispatch (ADR-0017) barraria o wakeup do kickback.
     */
    private UUID novoCeo(UUID board, UUID companyId) throws Exception {
        com.orquestrador.org.team.Team direcao = teams.save(new com.orquestrador.org.team.Team(
                UUID.randomUUID(), companyId, "Direcao", "topo", null));
        UUID ceoId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"role\":\"ceo\",\"teamId\":\""
                                + direcao.getId() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        direcao.designarArquiteto(ceoId);
        teams.save(direcao);
        return ceoId;
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
        UUID ceoId = novoCeo(board, companyId);

        // ADR-0037 (D2): o alvo carrega o time — o APPROVE resolve time→arquiteto (nasce com dono).
        com.orquestrador.org.team.Team time = teams.save(new com.orquestrador.org.team.Team(
                UUID.randomUUID(), companyId, "Produto", "Nicho", null));
        com.orquestrador.org.agent.Agent ana = new com.orquestrador.org.agent.Agent(
                UUID.randomUUID(), companyId, "Ana Arquiteta", "arquiteto", null);
        ana.assignTeam(time.getId());
        time.designarArquiteto(agents.save(ana).getId());
        teams.save(time);

        // Semeia uma proposta de DIREÇÃO pelo serviço (o caminho por-Run já é coberto no gate test).
        UUID approvalId = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Produto\",\"foco\":\"Nicho\","
                                + "\"title\":\"Focar no nicho dental\"}", "Focar no nicho dental",
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

    /**
     * F-C2 (C2/C6): a 7ª ação via HTTP — o card manda {@code fraseDoPrincipio} (a frase editada
     * pelo dono) e o Log filtrável devolve o princípio com a frase FINAL, {@code grau=PRINCIPIO}
     * e a linhagem C5 em evidência. O contrato que o front consome.
     */
    @Test
    void graduacaoViaHttpSelaComAFraseEditadaPeloDono() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID ceoId = novoCeo(board, companyId);

        UUID approvalId = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.GRADUATE_PRINCIPLE,
                        "{\"frase\":\"Toda campanha nasce com CAC alvo\",\"graduadaDe\":[1,2,3]}",
                        "Graduar principio de growth", "growth")).getId();

        mvc.perform(post("/api/companies/" + companyId + "/approvals/" + approvalId + "/decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\","
                                + "\"racional\":\"o padrao se repetiu em tres gates\","
                                + "\"fraseDoPrincipio\":\"Toda acao de growth nasce com CAC alvo e payback\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/api/companies/" + companyId + "/decisions?area=growth")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grau").value("PRINCIPIO"))
                .andExpect(jsonPath("$[0].oQueMudou")
                        .value("Toda acao de growth nasce com CAC alvo e payback"))
                .andExpect(jsonPath("$[0].approvalId").value(approvalId.toString()))
                .andExpect(jsonPath("$[0].evidencia")
                        .value(org.hamcrest.Matchers.containsString("#1, #2, #3")));
    }
}
