package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.12 (ADR-0026): o GC nasce LIGADO, mas com kill-switch. Desligado
 * ({@code orquestrador.workspace.gc.enabled=false}) o {@link WorkspaceReaper#sweepOnce()} não
 * coleta NADA — nem toca os alvos — mesmo com retenção 0 e Issue terminal vencida.
 */
@SpringBootTest(properties = {
        "orquestrador.workspace.gc.enabled=false",
        "orquestrador.workspace.gc.retention-days=0"
})
@ActiveProfiles("test")
class WorkspaceReaperKillSwitchTest {

    @Autowired
    private WorkspaceReaper reaper;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID companyId;

    @AfterEach
    void limpa() {
        if (companyId != null) {
            Path root = workspaces.root().resolve(companyId.toString());
            if (Files.exists(root)) {
                try (Stream<Path> tree = Files.walk(root)) {
                    tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    @Test
    void desligadoNaoColetaNadaMesmoComTerminalVencida() {
        companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "concluída", null).getId();
        Path workspace = workspaces.resolve(companyId, agentId, issueId);
        // Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter)
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.RUNNING)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.complete(companyId, issueId, runId, agentId); // DONE, retenção 0 -> seria coletada se ligado

        reaper.sweepOnce();

        assertThat(Files.exists(workspace)).as("kill-switch: nada coletado").isTrue();
        assertThat(activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .noneMatch(e -> "WORKSPACE_COLLECTED".equals(e.getAction()));
    }
}
