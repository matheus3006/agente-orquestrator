package com.orquestrador.governance.budget;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preflight: com o escopo ja pausado, um Run que entra na fila e CANCELADO no dispatch, sem executar
 * (a Issue nao e tocada). Fecha a janela entre "estourou" e "novo Run comecou".
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetPreflightTest {

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
    @Autowired
    private IssueService issues;

    @Test
    void runEntraComEscopoPausadoEhCanceladoSemExecutar() {
        // Contexto/H2 compartilhados entre classes: outros testes deixam runs QUEUED legítimos
        // (ex.: seam inbound de conversa não despacha). Drena antes para o ==0 ser SÓ deste run.
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia a fila global
        }
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 100, null);

        // ja estourado antes de qualquer Run
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 200, 0, 0);
        assertThat(budgets.invocationBlocked(companyId, agentId)).isTrue();

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        int dispatched = dispatcher.dispatchOnce();

        assertThat(dispatched).isEqualTo(0);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        // a Issue nunca foi tocada
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.OPEN);
    }
}
