package com.orquestrador.execution;

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
 * O fim do DONE fantasma (bug #1 do roadmap de ajustes, R22-A): um agente process SEM comando
 * acordado numa Issue era o run relâmpago verde (58ms, zero motor) que carimbava DONE. Agora o
 * no-op sinalizado pelo adapter atravessa o executor como {@link RunOutcome#noop} e a Issue
 * estaciona em BLOCKED — visível, com o porquê legível no erro do run.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunNoopTest {

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
    void processSemComandoNumaIssueNaoFechaAIssueERunNaoFingeVerde() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", null, "LOCAL"); // sem comando: o retrato do bug #1
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa", "descricao").getId();

        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        HeartbeatRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getLastError()).contains("no-op");

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }

    @Test
    void processSemComandoSemIssueSegueVerde() {
        // O "loop verde para agentes ainda não configurados" continua valendo FORA de Issue:
        // um wakeup avulso de agente sem comando não tem trabalho a mentir — segue no-op ok.
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", null, "LOCAL");
        UUID agentId = agents.save(agent).getId();

        UUID runId = wakeups.enqueue(companyId, agentId, null, "acordar").runId();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }
}
