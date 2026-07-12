package com.orquestrador.execution;

import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.EngineContainer;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.CostEvent;
import com.orquestrador.governance.budget.CostEventRepository;
import com.orquestrador.governance.budget.ScopeType;
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
 * Fatia B (ADR-0014): o {@link AdapterRunExecutor} injeta o motor (model/effort) do Agent no Context do
 * adapter e roteia os CostEvents emitidos para o ledger com {@code costCents=0} (Q7/ADR-0006) — sem abrir
 * incidente mesmo com policy, porque na assinatura o hard-stop de dólares é inócuo (tokens != centavos).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AdapterCostRoutingTest.CapturingAdapterConfig.class)
class AdapterCostRoutingTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private BudgetService budgets;
    @Autowired
    private CostEventRepository costs;
    @Autowired
    private CapturingAdapter capturingAdapter;

    @Test
    void executorInjetaMotorNoContextERoteiaTokensComCentavosZero() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();

        Agent agent = new Agent(UUID.randomUUID(), companyId, "Lyra", "architect", null);
        agent.configureRuntime("capturing", null, "LOCAL");
        agent.configureEngine(null, "opus", "high");
        UUID agentId = agents.save(agent).getId();

        // Policy no agente com limite mínimo (1 centavo): prova que tokens != centavos — costCents=0 não cruza.
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 1, null);

        UUID issueId = issues.create(companyId, "desenhar o motor", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // 1) O motor do Agent chegou no Context do adapter.
        assertThat(capturingAdapter.lastContext).isNotNull();
        assertThat(capturingAdapter.lastContext.model()).isEqualTo("opus");
        assertThat(capturingAdapter.lastContext.effort()).isEqualTo("high");

        // 2) O CostEvent foi persistido para o run com tokens REAIS e costCents=0. Filtra a MOEDA:
        // T.4 também cobra RUN_OPEN (orquestração) neste run — aqui interessa só o lançamento de token.
        List<CostEvent> recorded = costs.findByRunId(runId).stream()
                .filter(candidate -> candidate.getOrchestrationAction() == null)
                .toList();
        assertThat(recorded).hasSize(1);
        CostEvent event = recorded.get(0);
        assertThat(event.getProvider()).isEqualTo("anthropic");
        assertThat(event.getModel()).isEqualTo("opus");
        assertThat(event.getTokensInput()).isEqualTo(100);
        assertThat(event.getTokensOutput()).isEqualTo(40);
        assertThat(event.getCostCents()).isZero();

        // 3) costCents=0 é inócuo: o ledger em dólares fica 0 e NENHUM incidente abre (mesmo com policy).
        assertThat(budgets.spentForAgent(agentId)).isZero();
        assertThat(budgets.incidentsFor(companyId)).isEmpty();
    }

    @Test
    void emissaoOffBookChegaAoLedgerRotulada() {
        // F-N5 (ADR-0041, D5): a SEGUNDA emissão (subagente) chega ao ledger com off_book=true —
        // visível/rotulado, sem se misturar com o lançamento do turno principal.
        UUID companyId = companies.create("C2", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Nix", "architect", null);
        agent.configureRuntime("capturing-offbook", null, "LOCAL");
        agent.configureEngine(null, "opus", "high");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "delegar a subagente", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<CostEvent> recorded = costs.findByRunId(runId).stream()
                .filter(candidate -> candidate.getOrchestrationAction() == null)
                .toList();
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0).isOffBook()).isFalse();
        CostEvent offBook = recorded.get(1);
        assertThat(offBook.isOffBook()).isTrue();
        assertThat(offBook.getTokensOutput()).isEqualTo(27347);
    }

    @TestConfiguration
    static class CapturingAdapterConfig {
        @Bean
        CapturingOffBookAdapter capturingOffBookAdapter() {
            return new CapturingOffBookAdapter();
        }

        @Bean
        CapturingAdapter capturingAdapter() {
            return new CapturingAdapter();
        }
    }

    /** Adapter de teste: captura o Context recebido e emite um CostEvent (tokens reais) no sucesso. */
    static class CapturingAdapter implements AgentAdapter {
        volatile Context lastContext;

        @Override
        public String type() {
            return "capturing";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            this.lastContext = context;
            return Result.ok("sess-capturing",
                    List.of(new CostEmission("anthropic", "opus", 100, 40)));
        }
    }

    /** F-N5: emite DUAS CostEmission — a do turno principal e uma off-book (subagente). */
    static class CapturingOffBookAdapter implements AgentAdapter {
        @Override
        public String type() {
            return "capturing-offbook";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            return Result.ok("sess-offbook", List.of(
                    new CostEmission("anthropic", "opus", 100, 40),
                    new CostEmission("anthropic", "opus", 0, 27347, true)));
        }
    }
}
