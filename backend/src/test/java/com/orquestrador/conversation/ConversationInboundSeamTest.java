package com.orquestrador.conversation;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O seam inbound de T.3 (ADR-0018): mensagem-do-dono → turno PENDING por Conversation →
 * WakeupService acorda o CEO (issueId=null — o CEO é delegador puro, sem workspace de código).
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationInboundSeamTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatDispatcher dispatcher;

    /** Este teste enfileira SEM despachar (o seam é o assunto); não deixar QUEUED pros vizinhos. */
    @AfterEach
    void drenaFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void mensagemDoDonoCriaTurnoPendenteEAcordaOCeo() {
        UUID companyId = novaCompany();
        UUID ceoId = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null)).getId();
        UUID boardUser = UUID.randomUUID();

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "Precisamos montar o time de marketing", ActorType.BOARD, boardUser);

        List<ConversationTurn> thread = turns
                .findByConversationIdAndCompanyIdOrderByCreatedAtAsc(geral.getId(), companyId);
        assertThat(thread).hasSize(1);
        ConversationTurn turno = thread.get(0);
        assertThat(turno.getId()).isEqualTo(receipt.turnId());
        assertThat(turno.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(turno.getOwnerMessage()).isEqualTo("Precisamos montar o time de marketing");
        assertThat(turno.getAgentId()).isEqualTo(ceoId);
        assertThat(turno.getRunId()).isNull(); // o claim é do motor, não do post

        // O wakeup do CEO existe, QUEUED, sem Issue (delegador puro).
        List<HeartbeatRun> ativos = runs.findByAgentAndStatuses(ceoId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING));
        assertThat(ativos).hasSize(1);
        assertThat(ativos.get(0).getId()).isEqualTo(receipt.runId());
        assertThat(ativos.get(0).getIssueId()).isNull();
    }

    @Test
    void duasMensagensSeguidasCoalescemNoMesmoRunQueued() {
        UUID companyId = novaCompany();
        agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt primeira = conversations.postMessage(
                companyId, geral.getId(), "m1", ActorType.BOARD, UUID.randomUUID());
        ConversationService.TurnReceipt segunda = conversations.postMessage(
                companyId, geral.getId(), "m2", ActorType.BOARD, UUID.randomUUID());

        assertThat(primeira.runCreated()).isTrue();
        assertThat(segunda.runCreated()).isFalse();
        assertThat(segunda.runId()).isEqualTo(primeira.runId());
        assertThat(turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(geral.getId(), companyId))
                .hasSize(2); // os DOIS turnos ficam PENDING; o motor os atende um por Run
    }

    /**
     * Balcão único (ADR-0031, D2): com as 3 personas semeadas, a mensagem do dono cai SEMPRE no
     * Questionador — mesmo que outra persona seja mais antiga. Planejador e Executor são
     * back-office: nunca recebem inbound do dono.
     */
    @Test
    void mensagemDoDonoCaiNoQuestionador_balcaoUnico() {
        UUID companyId = novaCompany();
        Agent executor = new Agent(UUID.randomUUID(), companyId, "CEO Executor", "ceo", null);
        executor.definePersona("executor");
        agents.save(executor); // o MAIS ANTIGO — e mesmo assim não é o balcão
        Agent questionador = new Agent(UUID.randomUUID(), companyId, "CEO Questionador", "ceo", null);
        questionador.definePersona("questionador");
        UUID questionadorId = agents.save(questionador).getId();
        Agent planejador = new Agent(UUID.randomUUID(), companyId, "CEO Planejador", "ceo", null);
        planejador.definePersona("planejador");
        agents.save(planejador);

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "novo business: cafeteria", ActorType.BOARD, UUID.randomUUID());

        assertThat(turns.findById(receipt.turnId()).orElseThrow().getAgentId()).isEqualTo(questionadorId);
    }

    @Test
    void postMessageSemCeoContratadoE409() {
        UUID companyId = novaCompany();
        Conversation geral = conversations.open(companyId, "Geral");

        assertThatThrownBy(() -> conversations.postMessage(
                companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CEO");
    }

    @Test
    void assuntoDuplicadoNaMesmaCompanyE409EEmOutraCompanyPassa() {
        UUID companyId = novaCompany();
        conversations.open(companyId, "Geral");

        assertThatThrownBy(() -> conversations.open(companyId, "Geral"))
                .isInstanceOf(ConflictException.class);

        UUID outraCompany = novaCompany();
        assertThat(conversations.open(outraCompany, "Geral").getSubject()).isEqualTo("Geral");
    }
}
