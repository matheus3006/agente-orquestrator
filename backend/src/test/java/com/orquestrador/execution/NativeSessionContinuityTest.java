package com.orquestrador.execution;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.EngineContainer;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N1 (ADR-0038, D1): a regressão-mãe do laudo motor-nativo — o handle nativo era capturado e
 * NUNCA lido no caminho normal ({@code priorSession=null} literal). Aqui a continuidade nativa por
 * CONVERSA liga: o turno 2 de uma conversa resume a sessão do turno 1; conversa nova = sessão fresca.
 * Pipeline REAL do executor (probe captura o {@code priorSession} recebido por turno).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NativeSessionContinuityTest.ContinuityProbeConfig.class)
class NativeSessionContinuityTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private ContinuityProbe probe;
    @Autowired
    private AgentSessionStore agentSessions;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private IssueService issues;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void limpaOProbe() {
        probe.captures.clear();
        probe.nextSession = "sess-A";
        probe.failNext = false;
        probe.sessionLostOnResume = false;
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private void contrataCeoProbe(UUID companyId) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("continuity-probe", null, "LOCAL");
        ceo.configureEngine(null, "m", "e");
        agents.save(ceo);
    }

    private UUID contrataExecutorProbe(UUID companyId) {
        Agent exec = new Agent(UUID.randomUUID(), companyId, "Exec", "architect", null);
        exec.configureRuntime("continuity-probe", null, "LOCAL");
        exec.configureEngine(null, "m", "e");
        return agents.save(exec).getId();
    }

    /** A absorção (ADR-0037, V27) ancoraria a issue numa conversa; aqui o efeito, direto no schema. */
    private void ancoraIssueNaConversa(UUID issueId, UUID conversationId) {
        jdbc.update("update issue set conversation_id = ? where id = ?", conversationId, issueId);
    }

    private void dispatchAte(int max) {
        for (int i = 0; i < max && dispatcher.dispatchOnce() > 0; i++) {
            // no-op
        }
    }

    @Test
    void turno2ResumeASessaoDoTurno1() {
        UUID companyId = novaCompany();
        contrataCeoProbe(companyId);
        Conversation conv = conversations.open(companyId, "Fundo");

        // Turno 1: sessão FRESCA (nenhum handle ainda) → o probe devolve "sess-A" (persistido no settle).
        conversations.postMessage(companyId, conv.getId(), "primeira", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);
        // Turno 2: o mesmo chat → o executor lê o handle gravado e passa como priorSession.
        conversations.postMessage(companyId, conv.getId(), "segunda", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);

        assertThat(probe.captures).hasSize(2);
        assertThat(probe.captures.get(0).priorSession()).isNull();      // turno 1 = fresco
        assertThat(probe.captures.get(1).priorSession()).isEqualTo("sess-A"); // turno 2 resume a sessão do 1
    }

    @Test
    void turnoContinuadoEmagreceOPrompt_soltaOsBlocosEstaticos_mantemEstadoVivoEMensagem() {
        UUID companyId = novaCompany();
        contrataCeoProbe(companyId);
        Conversation conv = conversations.open(companyId, "Dieta");

        conversations.postMessage(companyId, conv.getId(), "primeira", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);
        conversations.postMessage(companyId, conv.getId(), "segunda", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);

        String promptTurno1 = probe.captures.get(0).prompt();
        String promptTurno2 = probe.captures.get(1).prompt();

        // Turno 1 (fresco): o prompt CHEIO carrega os blocos estáticos de conversa/persona.
        assertThat(promptTurno1)
                .contains("MANTENHA UMA CONVERSA")     // bloco estático de conversa-com-o-dono
                .contains("Regra de ouro do handoff");  // bloco estático da regra de ouro
        // Turno 2 (continuado): DIETA — os blocos estáticos saem (a sessão já os tem); o prompt fica
        // MENOR, mas mantém o estado vivo ATUALIZADO + a mensagem NOVA + o marcador de continuidade.
        assertThat(promptTurno2)
                .doesNotContain("MANTENHA UMA CONVERSA")
                .doesNotContain("Regra de ouro do handoff")
                .contains("Estado vivo da empresa")   // estado vivo (dinâmico) SEGUE
                .contains("segunda")                    // a mensagem nova do dono SEGUE
                .contains("sessão contínua");           // marcador de continuidade
        assertThat(promptTurno2.length()).isLessThan(promptTurno1.length());
    }

    @Test
    void conversaNovaComecaComSessaoFresca() {
        UUID companyId = novaCompany();
        contrataCeoProbe(companyId);
        Conversation primeira = conversations.open(companyId, "Assunto A");
        Conversation segunda = conversations.open(companyId, "Assunto B");

        conversations.postMessage(companyId, primeira.getId(), "a1", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);
        // Assunto NOVO = conversa nova = escopo de sessão novo → priorSession null, não vaza de A.
        conversations.postMessage(companyId, segunda.getId(), "b1", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);

        assertThat(probe.captures).hasSize(2);
        assertThat(probe.captures.get(0).priorSession()).isNull();
        assertThat(probe.captures.get(1).priorSession()).isNull(); // conversa B não herda a sessão de A
    }

    @Test
    void turnoQueFalhaAntesDeGravarPreservaOHandle_oProximoResumeAConversa() {
        // D1 condição (c) do handoff: handle salvo = último gravado COM SUCESSO. Um run que falha
        // antes de gravar (Result.fail ⟹ session=null ⟹ record() no-op) NÃO sobrescreve o handle —
        // o próximo turno resume a sessão ANTERIOR. Perde só o turno falhado, nunca a conversa.
        UUID companyId = novaCompany();
        contrataCeoProbe(companyId);
        Conversation conv = conversations.open(companyId, "Retry");
        UUID convId = conv.getId();
        UUID agentId = agents.findByCompanyId(companyId).get(0).getId();

        // Turno 1: sucesso → grava "sess-A".
        probe.nextSession = "sess-A";
        conversations.postMessage(companyId, convId, "t1", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);
        assertThat(agentSessions.currentHandle(agentId, convId)).contains("sess-A");

        // Turno 2: resume "sess-A" e FALHA antes de gravar (a sessão do CLI rotacionaria, mas nada
        // é persistido de um run que falhou) → o handle NÃO muda.
        probe.failNext = true;
        conversations.postMessage(companyId, convId, "t2", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);
        assertThat(agentSessions.currentHandle(agentId, convId))
                .as("o handle sobrevive ao turno falhado")
                .contains("sess-A");

        // Turno 3: volta a funcionar → resume o handle ANTERIOR (a conversa não morreu no turno 2).
        probe.failNext = false;
        probe.nextSession = "sess-C";
        conversations.postMessage(companyId, convId, "t3", ActorType.BOARD, UUID.randomUUID());
        dispatchAte(4);

        assertThat(probe.captures).hasSize(3);
        assertThat(probe.captures.get(1).priorSession()).isEqualTo("sess-A"); // t2 resumiu sess-A (e falhou)
        assertThat(probe.captures.get(2).priorSession()).isEqualTo("sess-A"); // t3 AINDA resume sess-A
    }

    // ---- F-N2 (ADR-0038, D3): continuidade nativa dos EXECUTORES, escopada à Issue ----

    @Test
    void runDeIssueComConversaResumeOHandleDaquelaIssue() {
        // D3: run N+1 do heartbeat continua a sessão do run N, escopado à Issue. A chave é reusada
        // via issue.conversation_id (V27) — SEM migração (V28 não consumida). Aqui um handle já
        // gravado (como se o run anterior o tivesse deixado) volta como priorSession.
        UUID companyId = novaCompany();
        UUID agentId = contrataExecutorProbe(companyId);
        UUID convId = conversations.open(companyId, "#7 — landing").getId();
        agentSessions.record(companyId, agentId, convId, "continuity-probe", "sess-ISSUE", UUID.randomUUID());
        UUID issueId = issues.create(companyId, "landing", null).getId();
        ancoraIssueNaConversa(issueId, convId);

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        dispatchAte(4);

        assertThat(probe.captures).hasSize(1);
        assertThat(probe.captures.get(0).priorSession()).isEqualTo("sess-ISSUE");
    }

    @Test
    void runDeIssueComConversaGravaOHandleParaORunSeguinte() {
        // O par do read: um run de Issue com conversa GRAVA o handle sob (agent, issue.conversation)
        // — sem isso, run N+1 não teria o que resumir (hoje o executor só gravava em turno).
        UUID companyId = novaCompany();
        UUID agentId = contrataExecutorProbe(companyId);
        UUID convId = conversations.open(companyId, "#8 — pipeline").getId();
        UUID issueId = issues.create(companyId, "pipeline", null).getId();
        ancoraIssueNaConversa(issueId, convId);
        probe.nextSession = "sess-NOVO";

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        dispatchAte(4);

        assertThat(agentSessions.currentHandle(agentId, convId)).contains("sess-NOVO");
    }

    @Test
    void runDeIssueSemConversaNaoGravaHandle() {
        // Issue sem conversa: nada a gravar (não há chave válida — conversation_id é NOT NULL no
        // agent_session). Confirmação do escopo sem migração: o executor não força um handle órfão.
        UUID companyId = novaCompany();
        UUID agentId = contrataExecutorProbe(companyId);
        UUID convIdSolta = conversations.open(companyId, "conversa avulsa").getId();
        UUID issueId = issues.create(companyId, "sem conversa", null).getId();
        probe.nextSession = "sess-X";

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        dispatchAte(4);

        assertThat(agentSessions.currentHandle(agentId, convIdSolta)).isEmpty();
    }

    @Test
    void runDeIssueSemConversaSegueFresco() {
        // Issue SEM conversa (gate/corretiva/legado — conversation_id null): nada a resumir, sessão
        // fresca. Zero regressão (é o baseline de hoje) e zero migração (a fatia confirmou o escopo).
        UUID companyId = novaCompany();
        UUID agentId = contrataExecutorProbe(companyId);
        UUID issueId = issues.create(companyId, "sem conversa", null).getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        dispatchAte(4);

        assertThat(probe.captures).hasSize(1);
        assertThat(probe.captures.get(0).priorSession()).isNull();
    }

    @Test
    void resumeDeSessaoSumidaEmiteRunSessionLostEReiniciaFresco() {
        // D3 requisito #7 (fallback barulhento): resume falhou (handle inválido/sessão sumida) →
        // evento visível RUN_SESSION_LOST + fresh start do workspace. NUNCA silêncio, NUNCA a issue
        // travada por sessão perdida (a verdade-fonte do executor é o workspace — re-derivar é barato).
        UUID companyId = novaCompany();
        UUID agentId = contrataExecutorProbe(companyId);
        UUID convId = conversations.open(companyId, "#9 — reindex").getId();
        agentSessions.record(companyId, agentId, convId, "continuity-probe", "sess-GONE", UUID.randomUUID());
        UUID issueId = issues.create(companyId, "reindex", null).getId();
        ancoraIssueNaConversa(issueId, convId);
        probe.sessionLostOnResume = true;
        probe.nextSession = "sess-FRESH";

        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();
        dispatchAte(4);

        // Duas execuções: a 1ª resumiu "sess-GONE" e reportou sessão perdida; a 2ª rodou FRESCA.
        assertThat(probe.captures).hasSize(2);
        assertThat(probe.captures.get(0).priorSession()).isEqualTo("sess-GONE");
        assertThat(probe.captures.get(1).priorSession()).isNull();
        // Evento visível — nunca silêncio (espírito do RUN_LEASE_LOST).
        Integer sessionLostEvents = jdbc.queryForObject(
                "select count(*) from heartbeat_run_event where run_id = ? and type = 'RUN_SESSION_LOST'",
                Integer.class, runId);
        assertThat(sessionLostEvents).isEqualTo(1);
        // O fresh SUCEDEU: o handle regravado é o NOVO (o sumido não sobreviveu ao fresh bem-sucedido).
        assertThat(agentSessions.currentHandle(agentId, convId)).contains("sess-FRESH");
    }

    @TestConfiguration
    static class ContinuityProbeConfig {
        @Bean
        ContinuityProbe continuityProbe() {
            return new ContinuityProbe();
        }
    }

    /** Captura o {@code priorSession} e o prompt de cada turno; devolve uma session configurável. */
    static class ContinuityProbe implements AgentAdapter {
        record Capture(String priorSession, String prompt) {
        }

        final List<Capture> captures = new ArrayList<>();
        volatile String nextSession = "sess-A";
        volatile boolean failNext = false;
        volatile boolean sessionLostOnResume = false;

        @Override
        public String type() {
            return "continuity-probe";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            captures.add(new Capture(context.priorSession(),
                    context.env().getOrDefault("ORQ_PROMPT", "")));
            if (sessionLostOnResume && context.priorSession() != null) {
                return Result.failSessionLost("probe: sessão nativa sumida (No conversation found)", List.of());
            }
            if (failNext) {
                return Result.fail("probe: falha simulada antes de gravar handle");
            }
            steps.emit("RESULT", "ok");
            return Result.ok(nextSession, List.of(new CostEmission("anthropic", "m", 10, 5)));
        }
    }
}
