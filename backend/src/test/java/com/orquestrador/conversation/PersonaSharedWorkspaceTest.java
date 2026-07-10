package com.orquestrador.conversation;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 (ADR-0031, D4): o workspace dos turnos de CEO com persona é COMPARTILHADO por conversa —
 * chaveado por {@code conversationId}, não por {@code agentId}. É o chão do bastão: o que uma
 * persona escreve ({@code .orq/conversa/...}, fora de materializedSubtrees) a próxima LÊ, mesmo
 * sendo outro agente. CEO legado (persona nula) segue no workspace por-agente de sempre.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaSharedWorkspaceTest {

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
    @Autowired
    private WakeupService wakeups;

    @Test
    void bastaoEscritoPorUmaPersonaELidoPelaProxima_crossAgente() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();

        // Questionador: o run ESCREVE o bastão no chão da conversa (o .orq sobrevive entre runs).
        Agent questionador = new Agent(UUID.randomUUID(), companyId, "CEO Questionador", "ceo", null);
        questionador.configureRuntime("process",
                "mkdir -p .orq/conversa && printf 'BASTAO-42' > .orq/conversa/bastao.txt && echo dossie-escrito",
                "LOCAL");
        questionador.definePersona("questionador");
        agents.save(questionador);

        // Planejador: OUTRO agente — o run LÊ o mesmo chão.
        Agent planejador = new Agent(UUID.randomUUID(), companyId, "CEO Planejador", "ceo", null);
        planejador.configureRuntime("process", "cat .orq/conversa/bastao.txt", "LOCAL");
        planejador.definePersona("planejador");
        UUID planejadorId = agents.save(planejador).getId();

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        ConversationService.TurnReceipt doDono = conversations.postMessage(
                companyId, negocio.getId(), "quero abrir uma cafeteria", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(turns.findById(doDono.turnId()).orElseThrow().getStatus()).isEqualTo(TurnStatus.ANSWERED);

        // O bastão cruza a fronteira de agente: turno-continuação do PLANEJADOR na MESMA conversa.
        ConversationTurn continuacao = turns.save(new ConversationTurn(
                UUID.randomUUID(), negocio.getId(), companyId, planejadorId, "[continuação — bastão]"));
        wakeups.enqueue(companyId, planejadorId, null, "test-baton:" + continuacao.getId());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        ConversationTurn atendido = turns.findById(continuacao.getId()).orElseThrow();
        assertThat(atendido.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(atendido.getCeoReply()).contains("BASTAO-42"); // leu o que o Questionador deixou
    }
}
