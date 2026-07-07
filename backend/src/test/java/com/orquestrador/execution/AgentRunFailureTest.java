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
 * Caminho de falha: processo com exit != 0 -> Run FAILED e a Issue e ESTACIONADA em BLOCKED com o lock
 * LIBERADO (recovery park-for-human, ADR-0011). Nada de marcar DONE no fracasso; nada de travar para
 * sempre (o board destrava depois via unblock).
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunFailureTest {

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
    void runQueFalhaNaoConcluiAIssue() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();

        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "exit 3", "LOCAL");
        UUID agentId = agents.save(agent).getId();

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        dispatcher.dispatchOnce();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        // Issue estacionada em BLOCKED, lock liberado (o board destrava depois; não trava para sempre).
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();
    }
}
