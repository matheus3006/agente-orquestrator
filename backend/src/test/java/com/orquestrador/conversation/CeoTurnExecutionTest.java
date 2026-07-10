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

    private UUID contrataCeoEchoComPersona(UUID companyId, String persona, String nome) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, nome, "ceo", null);
        ceo.configureRuntime("process", "printf '%s\\n' \"$ORQ_PROMPT\"", "LOCAL");
        ceo.definePersona(persona);
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
        // #N na linha da issue (ADR-0033/D4): o dono diz "a task 1 deu problema" → o CEO acha
        // #1 no estado vivo e responde citando o MESMO número do board. O exemplo ensina a notação.
        assertThat(atendido.getCeoReply()).contains("#1 [OPEN] Landing page do produto");
        assertThat(atendido.getCeoReply()).contains("Echo");
        assertThat(runs.findById(receipt.runId()).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void promptDobraOImperativoDeDestilarParaDentroDaCondicaoComTemplateInline() {
        UUID companyId = novaCompany();
        contrataCeoEcho(companyId);

        Conversation estrategia = conversations.open(companyId, "Estratégia");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, estrategia.getId(), "qual o próximo passo?", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // G2 (grill 2026-07-09): a instrução de handoff (M5) segue estática e aponta o bilhete DESTA
        // conversa, mas o imperativo de gravar mora DENTRO da condição — turno trivial NÃO grava.
        String promptEcoado = turno(receipt.turnId()).getCeoReply();
        assertThat(promptEcoado).contains("Regra de ouro do handoff");
        assertThat(promptEcoado).contains(".orq/handoff/" + estrategia.getId() + ".md");
        assertThat(promptEcoado).contains("SÓ SE");
        assertThat(promptEcoado).contains("senão NÃO grave nada");
        // O formato do bilhete vai INLINE (o motor codex não materializa a skill `distillation`);
        // a skill pode ser citada apenas como apoio opcional — nunca como pré-requisito.
        assertThat(promptEcoado).contains("Onde estou");
        assertThat(promptEcoado).contains("O que já fiz");
        assertThat(promptEcoado).contains("Próximo passo");
        assertThat(promptEcoado).contains("Decisões travadas");
        assertThat(promptEcoado).contains("Ponteiros");
        assertThat(promptEcoado).contains("apoio OPCIONAL");
        assertThat(promptEcoado).doesNotContain("Use a skill `distillation`");
    }

    @Test
    void promptInstruiConversaComVozPropriaEDelegacaoSoParaTrabalhoReal() {
        UUID companyId = novaCompany();
        contrataCeoEcho(companyId);

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "quem é você?", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // G1 (grill 2026-07-09): o CEO MANTÉM UMA CONVERSA — pergunta trivial se responde direto,
        // sem delegar (nem a sub-agentes internos do CLI); delegação só nos casos A/B/C do dono.
        String promptEcoado = turno(receipt.turnId()).getCeoReply();
        assertThat(promptEcoado).contains("MANTENHA UMA CONVERSA");
        assertThat(promptEcoado).contains("zero delegação");
        assertThat(promptEcoado).contains("novo business");
        assertThat(promptEcoado).contains("PALAVRAS DELE");
        assertThat(promptEcoado).contains("contratação");
        // "Delegador puro" (ADR-0018) fica escopado a ENTREGÁVEIS — a generalização que fazia o
        // CEO delegar até uma frase sai do prompt.
        assertThat(promptEcoado).contains("ENTREGÁVEIS");
        assertThat(promptEcoado).doesNotContain("nunca produza o conteúdo");
    }

    /**
     * M7 (ADR-0031): o QUESTIONADOR estende o prompt pós-G1/G2 — mantém a conversa em voz própria
     * E ganha os deveres da persona: dossiê APENDADO a cada turno (A1), confirma-antes (D5), o
     * contrato dos blocos tipados inline e o dever de re-grelha no kickback (D7).
     */
    @Test
    void promptDoQuestionadorEstendeG1G2ComDossieConfirmaAntesEBlocos() {
        UUID companyId = novaCompany();
        contrataCeoEchoComPersona(companyId, "questionador", "CEO Questionador");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, negocio.getId(), "quero abrir uma cafeteria", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        String prompt = turno(receipt.turnId()).getCeoReply();
        // G1/G2 preservados (o adendo ESTENDE, não contradiz)
        assertThat(prompt).contains("MANTENHA UMA CONVERSA");
        assertThat(prompt).contains("Regra de ouro do handoff");
        // A1: dossiê apendado A CADA turno, no workspace de conversa compartilhado
        assertThat(prompt).contains("QUESTIONADOR");
        assertThat(prompt).contains(".orq/conversa/" + negocio.getId() + "/dossie.md");
        assertThat(prompt).contains("APENDE");
        // D5: confirma-antes — o "sim" do dono precede a virada
        assertThat(prompt).contains("posso seguir");
        // D6/D7: contrato dos blocos tipados inline + dever de re-grelha
        assertThat(prompt).contains("```handoff");
        assertThat(prompt).contains("Re-grelha");
    }

    /**
     * Caso B (ADR-0034, §F do handoff): o Questionador ganha a instrução da correção de task
     * entregue — ACRÉSCIMO ao cabeçalho por-persona (G1/G2 e os deveres da 0031 intocados):
     * emitir ```correcao``` com SÓ o número (o verbatim é cópia mecânica do banco, nunca
     * transcrição) e, se a reclamação veio espalhada, pedir consolidação ao dono.
     */
    @Test
    void promptDoQuestionadorInstruiOCasoB() {
        UUID companyId = novaCompany();
        contrataCeoEchoComPersona(companyId, "questionador", "CEO Questionador");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, negocio.getId(), "a task #12 ficou ruim", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        String prompt = turno(receipt.turnId()).getCeoReply();
        assertThat(prompt).contains("```correcao");
        assertThat(prompt).contains("SÓ o número");
        assertThat(prompt).contains("byte a byte");
        assertThat(prompt).contains("me resume numa mensagem só");
        // G1/G2 e os deveres da 0031 seguem no prompt — acréscimo, não reescrita
        assertThat(prompt).contains("MANTENHA UMA CONVERSA");
        assertThat(prompt).contains("APENDE");
        assertThat(prompt).contains("```handoff");
    }

    /** M7: o PLANEJADOR é back-office — bastão como insumo, estratégia de ORQUESTRAÇÃO como saída. */
    @Test
    void promptDoPlanejadorEBackOfficeComEstrategiaECalibracao() {
        UUID companyId = novaCompany();
        // só o planejador na company → o fallback do resolvedor endereça o turno a ele (o truque
        // deixa o prompt back-office testável pelo MESMO seam público do post).
        contrataCeoEchoComPersona(companyId, "planejador", "CEO Planejador");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, negocio.getId(), "[continuação — bastão]", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        String prompt = turno(receipt.turnId()).getCeoReply();
        assertThat(prompt).contains("PLANEJADOR");
        assertThat(prompt).contains("back-office");
        assertThat(prompt).contains(".orq/conversa/" + negocio.getId() + "/estrategia.md");
        // D10: MAX 30/10 é teto de PROMPT; D12: estratégia é orquestração, não execução
        assertThat(prompt).contains("30");
        assertThat(prompt).contains("arquiteto");
        // D7: calibração — lacuna pequena vira Premissas; grande vira needs-grilling
        assertThat(prompt).contains("Premissas");
        assertThat(prompt).contains("```needs-grilling");
        assertThat(prompt).contains("```handoff");
        // back-office não conversa com o dono (D2)
        assertThat(prompt).doesNotContain("MANTENHA UMA CONVERSA");
        // a regra de ouro do bilhete vale POR PERSONA (D8: reset e virada são gatilhos ortogonais)
        assertThat(prompt).contains("Regra de ouro do handoff");
    }

    /** M7 + A2/A3: o EXECUTOR protocola SEM MEXER e emite ```proposta``` com racional por-agente. */
    @Test
    void promptDoExecutorProtocolaSemMexerComPropostaERacional() {
        UUID companyId = novaCompany();
        contrataCeoEchoComPersona(companyId, "executor", "CEO Executor");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, negocio.getId(), "[continuação — bastão]", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        String prompt = turno(receipt.turnId()).getCeoReply();
        assertThat(prompt).contains("EXECUTOR");
        assertThat(prompt).contains("back-office");
        // A2: protocola 1:1 com a estratégia; buraco = kickback, nunca conserto silencioso
        assertThat(prompt).contains("SEM MEXER");
        assertThat(prompt).contains("```needs-grilling");
        // E→time é o gate T3: um ```proposta``` por agente, com racional estruturado (A3)
        assertThat(prompt).contains("```proposta");
        assertThat(prompt).contains("racional");
        // A2: autonomia começa PÓS-approve
        assertThat(prompt).contains("PÓS-approve");
        assertThat(prompt).doesNotContain("MANTENHA UMA CONVERSA");
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
