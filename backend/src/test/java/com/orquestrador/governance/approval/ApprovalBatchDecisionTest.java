package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O lote do gate (ADR-0035, D9/D10/D14): UM POST decide N approvals reaproveitando o decide()
 * item a item — item que não commita (já decidida, inexistente, grelhada, dispatch quebrado) é
 * PULADO sem quebrar o lote, e a contagem sai do loop de graça. Board-only como o resto do gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalBatchDecisionTest {

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
    @Autowired
    private com.orquestrador.org.team.TeamRepository teams;
    @Autowired
    private com.orquestrador.org.agent.AgentRepository agents;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID novaCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        // ADR-0037 (D2): CREATE_ISSUE aprovada resolve time→arquiteto — o cenário precisa da corrente.
        com.orquestrador.org.team.Team time = teams.save(new com.orquestrador.org.team.Team(
                UUID.randomUUID(), companyId, "Dev", "Backlog"));
        com.orquestrador.org.agent.Agent ana = new com.orquestrador.org.agent.Agent(
                UUID.randomUUID(), companyId, "Ana Arquiteta", "arquiteto", null);
        ana.assignTeam(time.getId());
        time.designarArquiteto(agents.save(ana).getId());
        teams.save(time);
        return companyId;
    }

    private UUID novoCeo(UUID board, UUID companyId) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"role\":\"ceo\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID propoeIssue(UUID companyId, UUID ceoId, UUID conversationId, String titulo) {
        return approvals.propose(companyId, ceoId, conversationId, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Dev\",\"foco\":\"Backlog\",\"title\":\"" + titulo + "\"}",
                        titulo, "backlog")).getId();
    }

    @Test
    void recusarEmLoteAgregaOKickbackPorConversaDeOrigem() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        Conversation conversaA = conversations.open(companyId, "Expansão");
        Conversation conversaB = conversations.open(companyId, "Marketing");
        UUID daConversaA1 = propoeIssue(companyId, ceoId, conversaA.getId(), "Contratar backend");
        UUID daConversaA2 = propoeIssue(companyId, ceoId, conversaA.getId(), "Contratar frontend");
        UUID daConversaB = propoeIssue(companyId, ceoId, conversaB.getId(), "Campanha paga");

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"racional\":\"time acima do caixa atual\","
                                + "\"motivo\":\"sem orcamento neste trimestre\","
                                + "\"items\":[{\"approvalId\":\"" + daConversaA1 + "\"},"
                                + "{\"approvalId\":\"" + daConversaA2 + "\","
                                + "\"observacao\":\"frontend so depois do design system\"},"
                                + "{\"approvalId\":\"" + daConversaB + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(3))
                .andExpect(jsonPath("$.skipped").isEmpty())
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"));

        // D7: as DUAS recusas da conversa A viram UM turno síntese; a B ganha o dela.
        List<ConversationTurn> threadA = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversaA.getId(), companyId);
        assertThat(threadA).hasSize(1);
        assertThat(threadA.get(0).getAgentId()).isEqualTo(ceoId);
        assertThat(threadA.get(0).getOwnerMessage())
                .contains("2 propostas")
                .contains("sem orcamento neste trimestre")
                .contains("Contratar backend")
                .contains("Contratar frontend")
                .contains("observação: frontend so depois do design system");

        List<ConversationTurn> threadB = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversaB.getId(), companyId);
        assertThat(threadB).hasSize(1);
        assertThat(threadB.get(0).getOwnerMessage())
                .contains("Campanha paga")
                .contains("sem orcamento neste trimestre");

        // D8 na linha: a observação detalha o motivo comum na decisionReason do item.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/companies/" + companyId + "/approvals?status=REJECTED")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + daConversaA2 + "')].decisionReason")
                        .value("sem orcamento neste trimestre — observação: frontend so depois do design system"));
    }

    @Test
    void itemJaDecididoOuInexistenteEPuladoSemQuebrarOLote() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID jaDecidida = propoeIssue(companyId, ceoId, null, "Ja aprovada antes");
        UUID viva = propoeIssue(companyId, ceoId, null, "Ainda pendente");
        UUID fantasma = UUID.randomUUID();

        mvc.perform(post("/api/companies/" + companyId + "/approvals/" + jaDecidida + "/decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"prioridade da semana\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"fila validada\","
                                + "\"items\":[{\"approvalId\":\"" + jaDecidida + "\"},"
                                + "{\"approvalId\":\"" + fantasma + "\"},"
                                + "{\"approvalId\":\"" + viva + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].approvalId").value(jaDecidida.toString()))
                .andExpect(jsonPath("$.skipped[1].approvalId").value(fantasma.toString()))
                .andExpect(jsonPath("$.results[0].id").value(viva.toString()));
    }

    @Test
    void racionalAusenteGrelhaOsItensQueSeguemPendentesComRodadaContada() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID primeira = propoeIssue(companyId, ceoId, null, "Sem racional A");
        UUID segunda = propoeIssue(companyId, ceoId, null, "Sem racional B");

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\","
                                + "\"items\":[{\"approvalId\":\"" + primeira + "\"},"
                                + "{\"approvalId\":\"" + segunda + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(0))
                .andExpect(jsonPath("$.skipped.length()").value(2))
                .andExpect(jsonPath("$.skipped[0].motivo").value(
                        org.hamcrest.Matchers.startsWith("grill:")));

        // D14: o item grelhado segue PENDENTE (resolvível individualmente) e a rodada ficou contada.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/companies/" + companyId + "/approvals?status=PENDING")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].grillRounds").value(1));
    }

    @Test
    void dispatchQuebradoDesfazSoOProprioItem() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID antes = propoeIssue(companyId, ceoId, null, "Issue antes");
        // HIRE_AGENT sem name/role: o dispatch estoura DEPOIS do grill — a tx DESTE item desfaz.
        UUID quebrada = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.HIRE_AGENT,
                        "{\"reportsToId\":null}", "Hire sem alvo", "estrutura-org")).getId();
        UUID depois = propoeIssue(companyId, ceoId, null, "Issue depois");

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"fila validada\","
                                + "\"items\":[{\"approvalId\":\"" + antes + "\"},"
                                + "{\"approvalId\":\"" + quebrada + "\"},"
                                + "{\"approvalId\":\"" + depois + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(2))
                .andExpect(jsonPath("$.skipped.length()").value(1))
                .andExpect(jsonPath("$.skipped[0].approvalId").value(quebrada.toString()));

        // O item quebrado NÃO ficou meio-commitado: segue PENDING (deny-by-default por item).
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/companies/" + companyId + "/approvals?status=PENDING")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(quebrada.toString()));
    }

    @Test
    void recusaEmLoteSemMotivoComumE400() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID pendente = propoeIssue(companyId, ceoId, null, "Qualquer");

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"racional\":\"fora do foco\","
                                + "\"items\":[{\"approvalId\":\"" + pendente + "\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loteEBoardOnly() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID pendente = propoeIssue(companyId, ceoId, null, "Qualquer");
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/"
                                + ceoId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"eu mesmo\","
                                + "\"items\":[{\"approvalId\":\"" + pendente + "\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aprovarEmLoteCommitaCadaItemComOMesmoRacional() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = novaCompany(board);
        UUID ceoId = novoCeo(board, companyId);
        UUID primeira = propoeIssue(companyId, ceoId, null, "Landing nova");
        UUID segunda = propoeIssue(companyId, ceoId, null, "Fluxo de onboarding");

        mvc.perform(post("/api/companies/" + companyId + "/approvals/batch-decision")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"racional\":\"backlog validado na call\","
                                + "\"items\":[{\"approvalId\":\"" + primeira + "\"},"
                                + "{\"approvalId\":\"" + segunda + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decided").value(2))
                .andExpect(jsonPath("$.skipped").isEmpty())
                .andExpect(jsonPath("$.results[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.results[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.results[0].racional").value("backlog validado na call"));

        // As ações reais dispararam: as 2 issues existem no board.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Landing nova')]").exists())
                .andExpect(jsonPath("$[?(@.title == 'Fluxo de onboarding')]").exists());
    }
}
