package com.orquestrador.conversation;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.AgentSession;
import com.orquestrador.execution.AgentSessionRepository;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.EngineContainer;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O motor atendendo turnos de conversa (T.3, ADR-0018/A.8): prompt montado POR-RUN (estado vivo +
 * mensagem do dono) entregue ao CLI (env ORQ_PROMPT no arquétipo process; command nos adapters
 * ricos), reply capturado do output vira a thread, 1 turno = 1 Run (drain da fila), falha marca
 * FAILED, e o handle de sessão do adapter é persistido por (Agent, Conversation) — ADR-0002.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CeoTurnExecutionTest.SessionedAdapterConfig.class)
class CeoTurnExecutionTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private AgentSessionRepository sessions;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID contrataCeoEcho(UUID companyId) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        // O arquétipo process NÃO recebe o prompt como command (viraria sh -c de prosa): o prompt
        // chega pelo env ORQ_PROMPT — o echo devolve o prompt inteiro como resposta (smoke real).
        ceo.configureRuntime("process", "printf '%s\\n' \"$ORQ_PROMPT\"", "LOCAL");
        return agents.save(ceo).getId();
    }

    private ConversationTurn turno(UUID turnId) {
        return turns.findById(turnId).orElseThrow();
    }

    @Test
    void promptPorRunChegaNoCliEViraRespostaDoTurno() {
        UUID companyId = novaCompany();
        contrataCeoEcho(companyId);
        agents.save(new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null));
        issues.create(companyId, "Landing page do produto", null);

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "Montar o time de marketing", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        ConversationTurn atendido = turno(receipt.turnId());
        assertThat(atendido.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(atendido.getRunId()).isEqualTo(receipt.runId());
        // O reply é o prompt ecoado: prova que a mensagem E o estado vivo chegaram no CLI.
        assertThat(atendido.getCeoReply()).contains("Montar o time de marketing");
        assertThat(atendido.getCeoReply()).contains("Landing page do produto");
        assertThat(atendido.getCeoReply()).contains("Echo");
        assertThat(runs.findById(receipt.runId()).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void umTurnoPorRunComDrainDaFila() {
        UUID companyId = novaCompany();
        contrataCeoEcho(companyId);

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt primeira = conversations.postMessage(
                companyId, geral.getId(), "primeira pergunta", ActorType.BOARD, UUID.randomUUID());
        ConversationService.TurnReceipt segunda = conversations.postMessage(
                companyId, geral.getId(), "segunda pergunta", ActorType.BOARD, UUID.randomUUID());
        assertThat(segunda.runId()).isEqualTo(primeira.runId()); // coalesceu no mesmo run QUEUED

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(turno(primeira.turnId()).getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(turno(segunda.turnId()).getStatus()).isEqualTo(TurnStatus.PENDING); // 1 turno = 1 Run

        // O settle DRENOU a fila: um novo run atende o segundo turno.
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        ConversationTurn segundoAtendido = turno(segunda.turnId());
        assertThat(segundoAtendido.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(segundoAtendido.getRunId()).isNotEqualTo(turno(primeira.turnId()).getRunId());
        assertThat(segundoAtendido.getCeoReply()).contains("segunda pergunta");
    }

    @Test
    void falhaDoCliMarcaTurnoFailed() {
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "exit 3", "LOCAL");
        agents.save(ceo);

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "vai falhar", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(turno(receipt.turnId()).getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(runs.findById(receipt.runId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void adapterComSessaoPersisteHandlePorConversaEPrefereResultComoReply() {
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("sessioned", null, "LOCAL");
        UUID ceoId = agents.save(ceo).getId();

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt primeira = conversations.postMessage(
                companyId, geral.getId(), "m1", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // RESULT vence a prosa ASSISTANT como resposta da thread (disclosure: o resto fica no StepTrace).
        assertThat(turno(primeira.turnId()).getCeoReply()).isEqualTo("resposta final do ceo");

        List<AgentSession> handles = sessions.findByAgentId(ceoId);
        assertThat(handles).hasSize(1);
        assertThat(handles.get(0).getConversationId()).isEqualTo(geral.getId());
        assertThat(handles.get(0).getExternalSessionId()).isEqualTo("sess-abc");
        assertThat(handles.get(0).getLastRunId()).isEqualTo(primeira.runId());

        // Segundo turno na MESMA conversa: upsert (1 linha), lastRunId avança.
        ConversationService.TurnReceipt segunda = conversations.postMessage(
                companyId, geral.getId(), "m2", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        handles = sessions.findByAgentId(ceoId);
        assertThat(handles).hasSize(1);
        assertThat(handles.get(0).getLastRunId()).isEqualTo(turno(segunda.turnId()).getRunId());
    }

    @TestConfiguration
    static class SessionedAdapterConfig {
        @Bean
        AgentAdapter sessionedAdapter() {
            return new AgentAdapter() {
                @Override
                public String type() {
                    return "sessioned";
                }

                @Override
                public EngineContainer engineContainer() {
                    return EngineContainer.plainShell();
                }

                @Override
                public Result execute(Context context, StepSink steps) {
                    steps.emit("ASSISTANT", "pensando na resposta...");
                    steps.emit("RESULT", "resposta final do ceo");
                    return Result.ok("sess-abc");
                }
            };
        }
    }
}
