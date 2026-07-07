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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loop completo wakeup -> dispatch -> run. Prova a amarracao slice 1<->2: o Run faz o
 * checkout da Issue com o proprio runId e a conclui; o Run termina SUCCEEDED.
 */
@SpringBootTest
@ActiveProfiles("test")
class HeartbeatDispatchLifecycleTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository events;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void runExecutaChecksOutECompletaAIssue() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        WakeupOutcome wake = wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(wake.created()).isTrue();

        int dispatched = dispatcher.dispatchOnce();
        assertThat(dispatched).isEqualTo(1);

        HeartbeatRun run = runs.findById(wake.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        // amarração: a Issue foi concluida pelo Run (lock liberado, status DONE)
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();

        List<String> types = events.findByRunIdOrderByCreatedAtAsc(wake.runId()).stream()
                .map(HeartbeatRunEvent::getType).collect(Collectors.toList());
        assertThat(types).contains("RUN_STARTED", "RUN_SUCCEEDED");
    }
}
