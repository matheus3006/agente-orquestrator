package com.orquestrador.governance.budget;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N5 (ADR-0041, D5): a superfície de leitura do gasto off-book — {@code costEventsForRun}
 * devolve os lançamentos de TOKEN (exclui orquestração, T.4/V19 — moeda própria) de um run, na
 * ORDEM em que foram gravados, com o marcador {@code offBook} visível.
 */
@SpringBootTest
@ActiveProfiles("test")
class CostEventsForRunTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void devolveOsLancamentosDeTokenDoRunNaOrdemComOMarcadorOffBook() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = UUID.randomUUID();
        UUID outroRunId = UUID.randomUUID();

        budgets.recordCost(companyId, agentId, runId, null, "anthropic", "opus", 0, 100, 40);
        budgets.recordCost(companyId, agentId, runId, null, "anthropic", "opus", 0, 0, 27347, true);
        budgets.recordCost(companyId, agentId, outroRunId, null, "anthropic", "opus", 0, 999, 999);
        budgets.recordOrchestration(companyId, agentId, runId, OrchestrationAction.AGENT_SPAWN);

        List<CostEvent> events = budgets.costEventsForRun(companyId, runId);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).isOffBook()).isFalse();
        assertThat(events.get(0).getTokensInput()).isEqualTo(100);
        assertThat(events.get(1).isOffBook()).isTrue();
        assertThat(events.get(1).getTokensOutput()).isEqualTo(27347);
    }

    @Test
    void deOutraCompanyDevolveVazio() {
        UUID companyId = companies.create("C2", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID outraCompanyId = companies.create("C3", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID runId = UUID.randomUUID();
        budgets.recordCost(companyId, null, runId, null, "anthropic", "opus", 0, 10, 5);

        assertThat(budgets.costEventsForRun(outraCompanyId, runId)).isEmpty();
    }
}
