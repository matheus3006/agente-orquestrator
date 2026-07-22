package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.UnblockDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.12 (ADR-0026): o WorkspaceReaper coleta o workspace por-Issue de Issues TERMINAIS
 * (DONE/CANCELLED) com retenção vencida, nos dois alvos, world-driven por DERIVAÇÃO. Retenção 0
 * aqui = coleta imediata do que é terminal (a semântica da prova viva). Os casos LOCAIS rodam no
 * alvo real (dirs reais); os estados só-DOCKER (em-uso/falha) num alvo roteirizado, sem daemon.
 */
@SpringBootTest(properties = "orquestrador.workspace.gc.retention-days=0")
@ActiveProfiles("test")
class WorkspaceReaperTest {

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
    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private HeartbeatRunRepository runs;

    private final List<UUID> companiesToClean = new java.util.ArrayList<>();

    private ExecutionTarget local() {
        return targets.forType(ExecutionTarget.Type.LOCAL);
    }

    private record Ws(UUID companyId, UUID agentId, UUID issueId, Path dir) {
    }

    private UUID newCompany() {
        UUID id = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        companiesToClean.add(id);
        return id;
    }

    private UUID newLocalAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        return agents.save(agent).getId();
    }

    /** Cria (company, agent, issue) e MATERIALIZA o dir do workspace (como um run faria). */
    private Ws workspaceFor(UUID companyId, UUID agentId, UUID issueId) {
        return new Ws(companyId, agentId, issueId, workspaces.resolve(companyId, agentId, issueId));
    }

    /** Um Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter). */
    private UUID runReal(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.RUNNING)).getId();
    }

    private Ws done() {
        UUID companyId = newCompany();
        UUID agentId = newLocalAgent(companyId);
        UUID issueId = issues.create(companyId, "app com workspace", null).getId();
        Ws ws = workspaceFor(companyId, agentId, issueId);
        UUID runId = runReal(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);
        issues.complete(companyId, issueId, runId, agentId); // risco 1 < limiar -> DONE
        return ws;
    }

    @AfterEach
    void limpaOsDirsCriados() {
        for (UUID companyId : companiesToClean) {
            deleteQuietly(workspaces.root().resolve(companyId.toString()));
        }
    }

    private static void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // limpeza best-effort
                }
            });
        } catch (IOException ignored) {
            // idem
        }
    }

    private long collectedEvents(UUID companyId, UUID issueId) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(e -> "WORKSPACE_COLLECTED".equals(e.getAction()))
                .filter(e -> issueId.equals(e.getEntityId()))
                .count();
    }

    // ===== alvo LOCAL real =====

    @Test
    void coletaWorkspaceDeIssueDoneVencidaEAuditaComoSystem() {
        Ws ws = done();

        WorkspaceReaper.SweepReport report = reaper.sweepTarget(local());

        assertThat(Files.exists(ws.dir())).as("workspace de DONE vencida foi coletado").isFalse();
        assertThat(report.collected()).contains(ws.dir().toString());

        List<ActivityLog> eventos = activityLog.findByCompanyIdOrderByCreatedAtDesc(ws.companyId());
        assertThat(eventos)
                .anySatisfy(e -> {
                    assertThat(e.getAction()).isEqualTo("WORKSPACE_COLLECTED");
                    assertThat(e.getActorType()).isEqualTo(ActorType.SYSTEM.name());
                    assertThat(e.getActorId()).isNull();
                    assertThat(e.getEntityId()).isEqualTo(ws.issueId());
                    assertThat(e.getDetail()).contains("LOCAL").contains(ws.dir().toString());
                });
    }

    @Test
    void coletaWorkspaceDeIssueCancelledVencida() {
        UUID companyId = newCompany();
        UUID agentId = newLocalAgent(companyId);
        UUID issueId = issues.create(companyId, "cancelada", null).getId();
        Ws ws = workspaceFor(companyId, agentId, issueId);
        UUID runId = runReal(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.failure("boom")); // -> BLOCKED
        issues.unblock(companyId, issueId, UnblockDecision.CANCEL, ActorType.BOARD, UUID.randomUUID());

        reaper.sweepTarget(local());

        assertThat(Files.exists(ws.dir())).as("workspace de CANCELLED vencida foi coletado").isFalse();
    }

    @Test
    void naoColetaWorkspaceDeIssueNaoTerminal() {
        UUID companyId = newCompany();
        UUID agentId = newLocalAgent(companyId);
        UUID emProgresso = issues.create(companyId, "em progresso", null).getId();
        Ws wsProgresso = workspaceFor(companyId, agentId, emProgresso);
        issues.checkout(companyId, emProgresso, agentId,
                runReal(companyId, agentId, emProgresso)); // IN_PROGRESS, assignee fixado

        UUID bloqueada = issues.create(companyId, "bloqueada", null).getId();
        Ws wsBloqueada = workspaceFor(companyId, agentId, bloqueada);
        UUID runBloqueada = runReal(companyId, agentId, bloqueada);
        issues.checkout(companyId, bloqueada, agentId, runBloqueada);
        issues.settleAfterRun(companyId, bloqueada, runBloqueada, RunOutcome.failure("x")); // BLOCKED

        reaper.sweepTarget(local());

        assertThat(Files.exists(wsProgresso.dir())).as("IN_PROGRESS preservado").isTrue();
        assertThat(Files.exists(wsBloqueada.dir())).as("BLOCKED preservado para debug").isTrue();
    }

    @Test
    void naoColetaWorkspaceDeAgente() {
        UUID companyId = newCompany();
        UUID agentId = newLocalAgent(companyId);
        Path agentWorkspace = workspaces.resolve(companyId, agentId, null); // dir _agent

        reaper.sweepTarget(local());

        assertThat(Files.exists(agentWorkspace)).as("workspace de agente esta fora do GC").isTrue();
    }

    @Test
    void orfaoSoGeraWarnNaoColeta() {
        // Dir com o formato de workspace por-Issue mas SEM Issue no banco -> órfão: WARN, não coleta.
        UUID companyId = newCompany();
        Path orfao = workspaces.resolve(companyId, UUID.randomUUID(), UUID.randomUUID());

        WorkspaceReaper.SweepReport report = reaper.sweepTarget(local());

        assertThat(report.orphans()).contains(orfao.toString());
        assertThat(report.collected()).doesNotContain(orfao.toString());
        assertThat(Files.exists(orfao)).as("órfão jamais e deletado às cegas").isTrue();
    }

    // ===== estados só-DOCKER num alvo roteirizado (sem daemon) =====

    @Test
    void workspaceEmUsoPulaSemColetarEProximoCicloTentaDeNovo() {
        Ws ws = done();
        String ref = workspaces.volumeName(ws.companyId(), ws.agentId(), ws.issueId());
        ScriptedTarget emUso = new ScriptedTarget(ExecutionTarget.Type.DOCKER,
                List.of(ref), ExecutionTarget.IssueWorkspaceRemoval.IN_USE);

        WorkspaceReaper.SweepReport primeiro = reaper.sweepTarget(emUso);
        WorkspaceReaper.SweepReport segundo = reaper.sweepTarget(emUso);

        assertThat(primeiro.skipped()).contains(ref);
        assertThat(segundo.skipped()).contains(ref);
        assertThat(emUso.removeCalls).as("o proximo ciclo tenta de novo").isEqualTo(2);
        assertThat(collectedEvents(ws.companyId(), ws.issueId())).as("em-uso nunca audita").isZero();
    }

    @Test
    void falhaNaRemocaoPulaSemAuditar() {
        Ws ws = done();
        String ref = workspaces.volumeName(ws.companyId(), ws.agentId(), ws.issueId());
        ScriptedTarget falha = new ScriptedTarget(ExecutionTarget.Type.DOCKER,
                List.of(ref), ExecutionTarget.IssueWorkspaceRemoval.FAILED);

        WorkspaceReaper.SweepReport report = reaper.sweepTarget(falha);

        assertThat(report.skipped()).contains(ref);
        assertThat(report.collected()).doesNotContain(ref);
        assertThat(collectedEvents(ws.companyId(), ws.issueId())).isZero();
    }

    @Test
    void removeNoDockerAuditaSystemComTargetERef() {
        Ws ws = done();
        String ref = workspaces.volumeName(ws.companyId(), ws.agentId(), ws.issueId());
        ScriptedTarget removido = new ScriptedTarget(ExecutionTarget.Type.DOCKER,
                List.of(ref), ExecutionTarget.IssueWorkspaceRemoval.REMOVED);

        WorkspaceReaper.SweepReport report = reaper.sweepTarget(removido);

        assertThat(report.collected()).contains(ref);
        assertThat(activityLog.findByCompanyIdOrderByCreatedAtDesc(ws.companyId()))
                .anySatisfy(e -> {
                    assertThat(e.getAction()).isEqualTo("WORKSPACE_COLLECTED");
                    assertThat(e.getActorType()).isEqualTo(ActorType.SYSTEM.name());
                    assertThat(e.getEntityId()).isEqualTo(ws.issueId());
                    assertThat(e.getDetail()).contains("DOCKER").contains(ref);
                });
    }

    /** Alvo roteirizado: entrega um mundo fixo e um resultado de remoção fixo — sem daemon. */
    private static final class ScriptedTarget implements ExecutionTarget {
        private final Type type;
        private final List<String> world;
        private final IssueWorkspaceRemoval outcome;
        private int removeCalls = 0;

        ScriptedTarget(Type type, List<String> world, IssueWorkspaceRemoval outcome) {
            this.type = type;
            this.world = world;
            this.outcome = outcome;
        }

        @Override
        public Type type() {
            return type;
        }

        @Override
        public List<String> listIssueWorkspaces(WorkspaceResolver resolver) {
            return world;
        }

        @Override
        public IssueWorkspaceRemoval removeIssueWorkspace(WorkspaceResolver resolver, UUID companyId,
                                                          UUID agentId, UUID issueId) {
            removeCalls++;
            return outcome;
        }

        @Override
        public Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreviewServerStop stopPreviewServer(String serverName) {
            return PreviewServerStop.OK;
        }

        @Override
        public List<PreviewServerLive> listPreviewServers() {
            return List.of();
        }

        @Override
        public String previewServerLogTail(String serverName, int maxLines) {
            return "";
        }
    }
}
