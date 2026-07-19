package com.orquestrador.governance.approval;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.TurnStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O kickback de recusa (ADR-0035, D5/D6/D15): recusar uma proposta INFORMA o agente que a propôs —
 * um turno postado na conversa de ORIGEM, endereçado ao proponente, carregando o motivo do dono
 * VERBATIM (precedente do caso B: zero paráfrase). Sem conversa de origem não há para onde voltar:
 * a recusa commita normal e o kickback é descartado fail-soft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalRecusaKickbackTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID novaCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /**
     * Contratar exige time (ADR-0045): o CEO nasce num time de direção próprio e vira arquiteto
     * dele — sem arquiteto a trava do dispatch (ADR-0017) barraria o kickback que volta pra
     * conversa de origem, que é justamente o que este teste prova.
     */
    private UUID novoCeo(UUID board, UUID companyId) throws Exception {
        String teamsUrl = "/api/companies/" + companyId + "/teams";
        UUID timeId = idFrom(mvc.perform(post(teamsUrl)
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Direcao\",\"foco\":\"topo\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID ceoId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"role\":\"ceo\",\"teamId\":\"" + timeId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(patch(teamsUrl + "/" + timeId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + ceoId + "\"}"))
                .andExpect(status().isOk());
        return ceoId;
    }

    @Test
    void recusaIndividualComMotivoViraKickbackNaConversaDeOrigem() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        Conversation origem = conversations.open(companyId, "Expansão do time");

        UUID approvalId = approvals.propose(companyId, ceoId, origem.getId(), null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"title\":\"Landing nova\"}", "Landing nova", "backlog")).getId();

        mvc.perform(post("/api/companies/" + companyId + "/approvals/" + approvalId + "/decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"racional\":\"fora do foco do trimestre\","
                                + "\"reason\":\"landing so depois do produto validado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        List<ConversationTurn> thread = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                origem.getId(), companyId);
        assertThat(thread).hasSize(1);
        ConversationTurn kickback = thread.get(0);
        assertThat(kickback.getAgentId()).isEqualTo(ceoId);
        assertThat(kickback.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(kickback.getOwnerMessage())
                .contains("Recusa no gate")
                .contains("Landing nova")
                .contains("landing so depois do produto validado");
    }

    @Test
    void recusaSemConversaDeOrigemCommitaSemKickback() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);

        UUID approvalId = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"title\":\"Sem thread\"}", "Sem thread", "backlog")).getId();

        mvc.perform(post("/api/companies/" + companyId + "/approvals/" + approvalId + "/decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"racional\":\"duplicada\","
                                + "\"reason\":\"ja existe issue igual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
