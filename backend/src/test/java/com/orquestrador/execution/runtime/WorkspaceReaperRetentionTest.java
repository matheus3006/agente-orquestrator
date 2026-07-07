package com.orquestrador.execution.runtime;

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
 * F.12 (ADR-0026): a janela de retenção protege a Issue terminal ainda DENTRO do prazo — o período
 * de review/demo. Retenção de 100 anos = nada recém-terminado é coletado. Prova que o gate temporal
 * segura (o contraponto do retention-days=0 do {@code WorkspaceReaperTest}).
 */
@SpringBootTest(properties = "orquestrador.workspace.gc.retention-days=36500")
@ActiveProfiles("test")
class WorkspaceReaperRetentionTest {

    @Autowired
    private WorkspaceReaper reaper;
    @Autowired
    private ExecutionTargets targets;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

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
    void naoColetaIssueTerminalAindaDentroDaRetencao() {
        companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "recém-concluída", null).getId();
        Path workspace = workspaces.resolve(companyId, agentId, issueId);
        UUID runId = UUID.randomUUID();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.complete(companyId, issueId, runId); // DONE agora — vencerá só daqui a 100 anos

        WorkspaceReaper.SweepReport report = reaper.sweepTarget(targets.forType(ExecutionTarget.Type.LOCAL));

        assertThat(Files.exists(workspace)).as("DONE dentro da retenção é preservado").isTrue();
        assertThat(report.collected()).doesNotContain(workspace.toString());
    }
}
