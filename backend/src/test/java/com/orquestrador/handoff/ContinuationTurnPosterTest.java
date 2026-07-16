package com.orquestrador.handoff;

import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.conversation.persona.KickbackTurn;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ContinuationTurnPosterTest {

    @Autowired
    private ContinuationTurnPoster poster;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @AfterEach
    void drenaFilaGlobal() {
        // A fila de wakeups é GLOBAL no contexto compartilhado; um teste que posta sem despachar
        // (idempotência) deixa um Run QUEUED que envenenaria o dispatchOnce do próximo teste.
        for (int i = 0; i < 20 && dispatcher.dispatchOnce() > 0; i++) {
            // esvazia
        }
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID contrataCeoEcho(UUID companyId) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s\\n' \"$ORQ_PROMPT\"", "LOCAL");
        return agents.save(ceo).getId();
    }

    @Test
    void enfileiraUmTurnoContinuacaoComSentinelaQueOMotorReivindica() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Estratégia");

        poster.postContinuation(companyId, ceoId, conv.getId());

        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conv.getId(), companyId);
        assertThat(doConv).hasSize(1);
        ConversationTurn continuacao = doConv.get(0);
        assertThat(continuacao.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(ContinuationSentinel.isContinuation(continuacao.getOwnerMessage())).isTrue();

        // O motor reivindica o turno-continuação como qualquer outro (claimOldestPending não exige
        // owner_message) e o CLI o atende — a continuação vira uma resposta.
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(turns.findById(continuacao.getId()).orElseThrow().getStatus())
                .isEqualTo(TurnStatus.ANSWERED);
    }

    @Test
    void naoDobraAContinuacaoSeODisparoReexecutar() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Idempotente");

        poster.postContinuation(companyId, ceoId, conv.getId());
        poster.postContinuation(companyId, ceoId, conv.getId()); // o settle reexecutou (risco #10)

        long continuacoes = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conv.getId(), companyId)
                .stream().filter(t -> ContinuationSentinel.isContinuation(t.getOwnerMessage())).count();
        assertThat(continuacoes).isEqualTo(1); // idempotente: uma só continuação PENDING
    }

    @Test
    void aTravaAntiLoopCortaComAvisoVisivelAposCincoContinuacoes() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Encadeada");
        // 5 continuações já ANSWERED no histórico — o encadeamento máximo permitido (N=5).
        for (int i = 0; i < 5; i++) {
            ConversationTurn encadeada = new ConversationTurn(UUID.randomUUID(), conv.getId(), companyId,
                    ceoId, ContinuationSentinel.forConversation(conv.getId()));
            encadeada.answer("continuei " + i);
            turns.save(encadeada);
        }

        poster.postContinuation(companyId, ceoId, conv.getId()); // a 6ª tentativa → CORTE

        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conv.getId(), companyId);
        long continuacoes = doConv.stream()
                .filter(t -> ContinuationSentinel.isContinuation(t.getOwnerMessage())).count();
        assertThat(continuacoes).isEqualTo(5); // NÃO nasceu a 6ª

        // O corte é um turno ANSWERED VISÍVEL (não-sentinela) avisando o dono — o CEO não some (D7).
        ConversationTurn aviso = doConv.get(doConv.size() - 1);
        assertThat(ContinuationSentinel.isContinuation(aviso.getOwnerMessage())).isFalse();
        assertThat(aviso.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(aviso.getCeoReply()).contains("Parei após 5 continuações");
    }

    @Test
    void aThreadDoBoardEscondeAsContinuacoesMasMostraOTurnoDoDono() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Board");
        // A pergunta do dono é um turno normal, VISÍVEL.
        ConversationTurn doDono = new ConversationTurn(UUID.randomUUID(), conv.getId(), companyId, ceoId,
                "pergunta do dono");
        doDono.answer("resposta do ceo");
        turns.save(doDono);
        // Uma continuação (sentinela) — interna, INVISÍVEL na conversa (D5).
        poster.postContinuation(companyId, ceoId, conv.getId());

        List<ConversationTurn> thread = conversations.thread(companyId, conv.getId());

        assertThat(thread).noneMatch(t -> ContinuationSentinel.isContinuation(t.getOwnerMessage()));
        assertThat(thread).extracting(ConversationTurn::getOwnerMessage).containsExactly("pergunta do dono");
    }

    // ── Park-for-human (ADR-0011): o handoff de contexto NÃO retoma enquanto um kickback espera o dono.
    // Sem isto, o resume re-bate a mesma lacuna e nasce o loop kickback↔continuação (nenhuma das duas
    // travas isoladas o via — cada uma conta só o próprio tipo de turno; a alternância zera as duas).

    @Test
    void naoContinuaEnquantoUmKickbackEsperaRespostaDoDono() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Grilling");
        // O Questionador re-grelhou o dono (kickback já respondido) e ESPERA a resposta da lacuna.
        ConversationTurn kickback = new ConversationTurn(UUID.randomUUID(), conv.getId(), companyId, ceoId,
                KickbackTurn.ownerMessage(CeoPersona.PLANEJADOR, "confirma o ICP clínicas odontológicas?"));
        kickback.answer("Qual é o cliente-alvo? Confirma clínicas odontológicas?");
        turns.save(kickback);

        poster.postContinuation(companyId, ceoId, conv.getId()); // a ADR-0030 tentaria retomar o plano

        // PARK: nenhuma continuação nasce — a conversa espera o dono (o loop não começa).
        long continuacoes = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conv.getId(), companyId)
                .stream().filter(t -> ContinuationSentinel.isContinuation(t.getOwnerMessage())).count();
        assertThat(continuacoes).isZero();
    }

    @Test
    void continuaQuandoODonoJaRespondeuOKickback() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeoEcho(companyId);
        Conversation conv = conversations.open(companyId, "Respondido");
        ConversationTurn kickback = new ConversationTurn(UUID.randomUUID(), conv.getId(), companyId, ceoId,
                KickbackTurn.ownerMessage(CeoPersona.PLANEJADOR, "confirma o ICP?"));
        kickback.answer("Confirma clínicas odontológicas como ICP?");
        turns.save(kickback);
        // O dono VOLTOU e respondeu a lacuna — a régua zera; o plano pode retomar por contexto.
        ConversationTurn doDono = new ConversationTurn(UUID.randomUUID(), conv.getId(), companyId, ceoId,
                "Sim, confirmo clínicas odontológicas como ICP inicial");
        doDono.answer("Ótimo — sigo com esse ICP.");
        turns.save(doDono);

        poster.postContinuation(companyId, ceoId, conv.getId());

        // NÃO está parado: o dono respondeu depois do kickback — a continuação nasce normalmente.
        long continuacoes = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conv.getId(), companyId)
                .stream().filter(t -> ContinuationSentinel.isContinuation(t.getOwnerMessage())).count();
        assertThat(continuacoes).isEqualTo(1);
    }
}
