package com.orquestrador.execution;

import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execucao REAL (slice 3) via ExecutionTarget LOCAL: um Run roda um processo de verdade no
 * Workspace, captura stdout como StepTrace, cria um arquivo no Workspace, e conclui a Issue.
 * (O mesmo caminho roda em container quando o ExecutionTarget e DOCKER.)
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRunExecutionTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository events;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void runExecutaProcessoRealCapturaStdoutEEscreveNoWorkspace() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();

        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "printf 'linha-a\\nlinha-b\\n'; echo ola > marker.txt", "LOCAL");
        UUID agentId = agents.save(agent).getId();

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // Run concluiu e a Issue foi finalizada
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);

        // O processo rodou no Workspace e criou o arquivo
        Path workspace = workspaces.resolve(companyId, agentId, issueId);
        Path marker = workspace.resolve("marker.txt");
        assertThat(Files.exists(marker)).isTrue();
        assertThat(Files.readString(marker).trim()).isEqualTo("ola");

        // O stdout virou StepTrace
        List<String> stdout = events.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(e -> e.getType().equals("STDOUT"))
                .map(HeartbeatRunEvent::getDetail)
                .toList();
        assertThat(stdout).containsExactlyInAnyOrder("linha-a", "linha-b");
    }
}
