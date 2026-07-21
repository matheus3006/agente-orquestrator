package com.orquestrador.execution;

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
 * <b>Wakeup SEM issue ⇒ o gestor fala e ninguém escuta</b> (memória
 * {@code gate-de-saida-so-dispara-com-issueid}).
 *
 * <p>O executor computa {@code pontaDoExecutor = turno == null && issueId != null}. Um run de wakeup
 * sem issue cai fora dos dois ramos — não é turno de conversa, não é run de Issue — e os 4 gates de
 * fim-de-run são PULADOS. Um bloco {@code ```dirigir```} / {@code ```parecer```} /
 * {@code ```pedido-skill```} emitido no STDOUT nesse run é descartado <b>sem erro, sem aviso, sem
 * StepTrace</b>. O agente exerce autoridade que tem, e o ato evapora.
 *
 * <p><b>O remédio desta fatia é o anti-silêncio, não o roteamento</b> (ADR-0037 D1). Rotear o bloco
 * órfão para algum destino é pergunta de desenho — para QUEM, sob qual autoridade, com qual issue de
 * ancoragem — e pertence a um grill, não a um conserto de borda. O que não pode continuar é a perda
 * MUDA: quem lê o run tem de ver que houve um bloco e por que ele não valeu.
 *
 * <p>Ponta a ponta de propósito (wakeup real → {@code dispatchOnce} real → adapter emitindo STDOUT):
 * o defeito é justamente a FIAÇÃO — qual ramo o run cai — e um teste do miolo isolado não a prova.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(BlocoDeSaidaSemIssueNaoSomeEmSilencioTest.AdapterQueEmiteBlocoConfig.class)
class BlocoDeSaidaSemIssueNaoSomeEmSilencioTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private UUID novoAgente(UUID companyId, String tipoDeAdapter) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Gestor", "manager", null);
        agent.configureRuntime(tipoDeAdapter, null, "LOCAL");
        return agents.save(agent).getId();
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void wakeupSEMIssueComBlocoNaSaida_deixaStepTraceEmVezDeSilencio() {
        UUID companyId = novaCompany();
        UUID agentId = novoAgente(companyId, "emite-bloco");

        UUID runId = wakeups.enqueue(companyId, agentId, null, "acorda e opina").runId();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(runId))
                .as("o bloco foi emitido e descartado — isso TEM de aparecer no rastro do run")
                .anySatisfy(evento -> {
                    assertThat(evento.getType()).isEqualTo("BLOCO_DE_SAIDA_SEM_DESTINO");
                    assertThat(evento.getDetail()).contains("dirigir");
                });
    }

    /**
     * O par negativo que impede o aviso de virar ruído: um wakeup sem issue cuja saída não carrega
     * bloco nenhum é o caso comum, e não pode ganhar um step de descarte a cada run.
     */
    @Test
    void wakeupSEMIssueESEMBloco_naoGanhaAvisoNenhum() {
        UUID companyId = novaCompany();
        UUID agentId = novoAgente(companyId, "sem-bloco");

        UUID runId = wakeups.enqueue(companyId, agentId, null, "acorda e trabalha").runId();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(runId))
                .noneSatisfy(evento ->
                        assertThat(evento.getType()).isEqualTo("BLOCO_DE_SAIDA_SEM_DESTINO"));
    }

    @TestConfiguration
    static class AdapterQueEmiteBlocoConfig {
        @Bean
        AdapterQueEmiteBloco adapterQueEmiteBloco() {
            return new AdapterQueEmiteBloco();
        }

        @Bean
        AdapterSemBloco adapterSemBloco() {
            return new AdapterSemBloco();
        }
    }

    /** Emite pelo STDOUT um bloco ```dirigir``` bem-formado — o que um gestor de verdade emitiria. */
    static class AdapterQueEmiteBloco implements AgentAdapter {
        @Override
        public String type() {
            return "emite-bloco";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            steps.emit("STDOUT", "avaliei o braço e vou dirigir uma issue:");
            steps.emit("STDOUT", "```dirigir");
            steps.emit("STDOUT", "{\"agente\": \"Nix\", \"titulo\": \"refazer o cálculo\"}");
            steps.emit("STDOUT", "```");
            return Result.ok("sess-emite-bloco", List.of());
        }
    }

    /** O caso comum: trabalha e não emite bloco de autoridade nenhum. */
    static class AdapterSemBloco implements AgentAdapter {
        @Override
        public String type() {
            return "sem-bloco";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            steps.emit("STDOUT", "revisei o quadro do time, nada a fazer nesta passada.");
            return Result.ok("sess-sem-bloco", List.of());
        }
    }
}
