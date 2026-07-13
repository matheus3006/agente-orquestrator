package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-AT2 (grill agente-como-tool D6): SÓ o run de EXECUTOR (turno==null && issueId!=null) em alvo LOCAL
 * ganha o servidor MCP-stdio do KnowAll no workspace. Prova o GATE de wiring no {@code AdapterRunExecutor}
 * — a materialização de fato (search/fetch mid-run com CLIs reais) é o /verify. Precisa de servidor web
 * de verdade ({@code RANDOM_PORT}) para o {@link McpCallbackAddress} capturar a porta loopback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpServerOnExecutorRunTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private UUID contrataExecutor(UUID companyId) {
        Agent executor = new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null);
        executor.configureRuntime("process", "echo ok", "LOCAL");
        return agents.save(executor).getId();
    }

    @Test
    void oRunDeExecutorMaterializaOServidorMcpNoWorkspace() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID executorId = contrataExecutor(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path server = workspaces.resolve(companyId, executorId, issueId)
                .resolve(KnowAllMcpMaterializer.SCRIPT_RELATIVE_PATH);
        assertThat(Files.exists(server)).as("servidor MCP do KnowAll materializado no run de executor").isTrue();
        assertThat(server.toString()).contains(".orq/mcp/knowall-server.js");
    }

    @Test
    void oRunSemIssueNaoMaterializaOServidorMcp() {
        // O gate exige issueId != null (run de EXECUTOR de uma Issue) — um wakeup ocioso sem Issue não
        // ganha o servidor MCP (mesma cláusula que exclui as personas: turno != null também não ganha).
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID executorId = contrataExecutor(companyId);

        wakeups.enqueue(companyId, executorId, null, "ocioso");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path server = workspaces.resolve(companyId, executorId, null)
                .resolve(KnowAllMcpMaterializer.SCRIPT_RELATIVE_PATH);
        assertThat(Files.exists(server)).as("run sem Issue não ganha o servidor MCP").isFalse();
    }
}
