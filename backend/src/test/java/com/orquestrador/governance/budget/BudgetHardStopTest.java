package com.orquestrador.governance.budget;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
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
 * Hard-stop reativo (ledger = verdade): cruzar o limite abre incidente, PAUSA o escopo e CANCELA o
 * trabalho em fila. O override (resolve) destrava e o agente volta a operar.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetHardStopTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void cruzarOLimiteCancelaTrabalhoEPausaAteOverride() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 100, null);

        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId();

        // gasto sob o limite: nada acontece
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 60, 100, 50);
        assertThat(budgets.invocationBlocked(companyId, agentId)).isFalse();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);

        // gasto cruza o limite (60+50=110 >= 100): incidente + cancelamento + pausa
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 50, 80, 40);
        assertThat(budgets.invocationBlocked(companyId, agentId)).isTrue();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(budgets.incidentsFor(companyId)).hasSize(1);
        assertThat(budgets.incidentsFor(companyId).get(0).getStatus()).isEqualTo(IncidentStatus.OPEN);

        // override de governanca destrava
        UUID incidentId = budgets.incidentsFor(companyId).get(0).getId();
        budgets.resolveIncident(companyId, incidentId);
        assertThat(budgets.invocationBlocked(companyId, agentId)).isFalse();

        // agente volta a operar
        UUID run2 = wakeups.enqueue(companyId, agentId, null, "de novo").runId();
        dispatcher.dispatchOnce();
        assertThat(runs.findById(run2).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }
}
