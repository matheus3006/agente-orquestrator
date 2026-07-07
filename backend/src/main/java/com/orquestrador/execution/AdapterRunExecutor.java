package com.orquestrador.execution;

import com.orquestrador.conversation.ConversationTurnCoordinator;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.memory.CompanyMemoryMaterializer;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.preview.PreviewArtifactCapturer;
import com.orquestrador.preview.server.PreviewServerManifestRegistrar;
import com.orquestrador.preview.server.PreviewServerSupervisor;
import com.orquestrador.skills.SkillMaterializer;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.work.issue.CheckoutResult;
import com.orquestrador.work.issue.IssueService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executor real (slice 3): resolve o Workspace, faz o checkout da Issue com o runId, invoca o
 * AgentAdapter no ExecutionTarget do agente (local/docker), grava o StepTrace e conclui a Issue.
 * Substitui o StubRunExecutor. (Fatia 6: materializa o SkillSet do agente no workDir apos resolve.)
 *
 * <p>T.3 (ADR-0018): um Run SEM Issue pode estar atendendo um TURNO de conversa — o claim no
 * {@link ConversationTurnCoordinator} decide. Com turno, o {@code command} é montado POR-RUN
 * (ADR-0004 emendado) e a resposta capturada do output liquida o turno no settle.
 */
@Component
public class AdapterRunExecutor implements RunExecutor {

    /** O env por onde o prompt do turno chega a QUALQUER runtime (o process não o aceita como command). */
    static final String PROMPT_ENV = "ORQ_PROMPT";

    private final AgentRepository agents;
    private final IssueService issues;
    private final WorkspaceResolver workspaces;
    private final ExecutionTargets targets;
    private final AdapterRegistry adapters;
    private final HeartbeatRunStore store;
    private final SkillSetService skillSets;
    private final SkillMaterializer materializer;
    private final BudgetService budget;
    private final ConversationTurnCoordinator turnCoordinator;
    private final AgentSessionStore agentSessions;
    private final CompanyMemoryMaterializer memoryMaterializer;
    private final PreviewArtifactCapturer previews;
    private final PreviewServerManifestRegistrar serverManifests;
    private final PreviewServerSupervisor previewServers;

    public AdapterRunExecutor(AgentRepository agents, IssueService issues, WorkspaceResolver workspaces,
                              ExecutionTargets targets, AdapterRegistry adapters, HeartbeatRunStore store,
                              SkillSetService skillSets, SkillMaterializer materializer, BudgetService budget,
                              ConversationTurnCoordinator turnCoordinator, AgentSessionStore agentSessions,
                              CompanyMemoryMaterializer memoryMaterializer, PreviewArtifactCapturer previews,
                              PreviewServerManifestRegistrar serverManifests,
                              PreviewServerSupervisor previewServers) {
        this.agents = agents;
        this.issues = issues;
        this.workspaces = workspaces;
        this.targets = targets;
        this.adapters = adapters;
        this.store = store;
        this.skillSets = skillSets;
        this.materializer = materializer;
        this.budget = budget;
        this.turnCoordinator = turnCoordinator;
        this.agentSessions = agentSessions;
        this.memoryMaterializer = memoryMaterializer;
        this.previews = previews;
        this.serverManifests = serverManifests;
        this.previewServers = previewServers;
    }

    @Override
    public RunOutcome execute(RunContext runContext) {
        RunOutcome outcome = runToOutcome(runContext);
        // F.9 (ADR-0021): captura do preview APOS o sucesso e ANTES do settle, FORA de transação —
        // quando a review nascer (dentro do settle), o artefato já existe. Best-effort: o capturador
        // garante que nenhuma falha de captura vira exceção (o settle abaixo é obrigatório).
        if (outcome.succeeded()) {
            previews.captureIfPublished(runContext);
            // F.10 (ADR-0023): a DECLARACAO de servidor e registrada no mesmo ponto — quando a
            // review nascer, o card ja sabe que o run sob revisao declarou (run_id == run_id).
            serverManifests.registerIfDeclared(runContext);
        }
        // §7 (ADR-0011): TODA saída terminal liquida a Issue — sucesso→DONE, falha→BLOCKED — sempre
        // liberando o lock. O guard (execution_lock_run_id=runId) torna no-op as falhas ANTES do checkout
        // e os checkout-conflicts (a Issue-vítima do outro run fica intacta).
        if (runContext.issueId() != null) {
            issues.settleAfterRun(runContext.companyId(), runContext.issueId(), runContext.runId(), outcome);
        }
        return outcome;
    }

    private RunOutcome runToOutcome(RunContext runContext) {
        Agent agent = agents.findById(runContext.agentId()).orElse(null);
        if (agent == null) {
            return RunOutcome.failure("agente nao encontrado: " + runContext.agentId());
        }

        // Fatia 7a: prepara o workspace ANTES do checkout — materializa a UNIÃO base∪anexos-da-Issue
        // (resolveForRun colapsa por skill com ANEXO-VENCE-A-BASE; anexos de Issue fechada não entram).
        // Uma falha de I/O aqui vira RunOutcome.failure; por ser ANTES do checkout, não deixa a Issue
        // travada por um run morto (o checkout ainda não ocorreu).
        Path workDir = workspaces.resolve(runContext.companyId(), runContext.agentId(), runContext.issueId());
        try {
            materializer.materialize(workDir,
                    skillSets.resolveForRun(runContext.companyId(), runContext.agentId(), runContext.issueId()));
        } catch (RuntimeException exception) {
            return RunOutcome.failure("falha ao materializar o SkillSet: " + exception.getMessage());
        }

        // Amarração slice 1<->2<->3: o Run detem a Issue via checkout atomico com o proprio runId.
        if (runContext.issueId() != null) {
            CheckoutResult checkout = issues.checkout(runContext.companyId(), runContext.issueId(), runContext.agentId(), runContext.runId());
            if (!checkout.acquired()) {
                return RunOutcome.failure("checkout da issue falhou (ja em execucao por outro run)");
            }
            // F.10 (ADR-0023 §3b): o run novo vai mutar o workspace — o preview vivo do estado
            // anterior morre AQUI (best-effort: o supervisor nunca lanca; o run segue).
            previewServers.teardownOnCheckout(runContext.companyId(), runContext.issueId());
        }

        ExecutionTarget target = targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()));
        AgentAdapter adapter = adapters.forType(agent.getAdapterType());

        // T.3: Run sem Issue pode ser um TURNO de conversa (o claim decide). Com turno, o command
        // vira o prompt por-Run nos adapters ricos; o arquétipo process o recebe SÓ pelo env
        // (prosa em sh -c seria executada como shell). O claim vem DEPOIS do preparo de workspace:
        // uma falha de I/O acima não marca turno FAILED (o turno segue PENDING para o re-post).
        ConversationTurnCoordinator.TurnAssignment turno = runContext.issueId() == null
                ? turnCoordinator.claimFor(runContext.companyId(), runContext.agentId(), runContext.runId()).orElse(null)
                : null;
        String command = agent.getRuntimeCommand();
        Map<String, String> env = Map.of();
        RunFinalOutput replyCollector = null;
        if (turno != null) {
            // A carta corrente vira .claude/CLAUDE.md no workspace do CEO (ADR-0018/0019): memória
            // durável pelo canal nativo do CLI; o prompt carrega só o estado VIVO. Falha de I/O
            // aqui liquida o turno (o claim já ocorreu — FAILED visível > PENDING pendurado).
            try {
                memoryMaterializer.materialize(workDir, runContext.companyId());
            } catch (RuntimeException exception) {
                turnCoordinator.settleFailed(turno.turnId());
                return RunOutcome.failure("falha ao materializar a carta: " + exception.getMessage());
            }
            env = Map.of(PROMPT_ENV, turno.prompt());
            if (adapter.acceptsPromptAsCommand()) {
                command = turno.prompt();
            }
            replyCollector = new RunFinalOutput();
        }

        // Escritor de workspace (par do leitor F.9/ADR-0021): a arvore que os materializers
        // deixaram no workDir do HOST so existe para o Run se atravessar o seam — no DOCKER o
        // workspace e um volume nomeado que o filesystem do backend nao enxerga (smoke F.9d: a
        // skill anexada nunca chegou em /workspace/.claude/skills). A carta so entra em run de
        // turno (espelha QUEM materializou). Falha aqui e failure LEGIVEL, e no turno liquida
        // FAILED como a carta acima — rodar sem a arvore seria divergencia silenciosa.
        String volumeName = workspaces.volumeName(runContext.companyId(), runContext.agentId(), runContext.issueId());
        List<String> materializedSubtrees = turno != null
                ? List.of(SkillMaterializer.SKILLS_SUBDIR, CompanyMemoryMaterializer.CLAUDE_MD)
                : List.of(SkillMaterializer.SKILLS_SUBDIR);
        ExecutionTarget.WorkspaceFilesWrite workspaceWrite = target.writeWorkspaceFiles(
                new ExecutionTarget.Spec(workDir, List.of(), Map.of(),
                        adapter.engineContainer().image(), volumeName, List.of()),
                materializedSubtrees);
        if (workspaceWrite.failed()) {
            if (turno != null) {
                turnCoordinator.settleFailed(turno.turnId());
            }
            return RunOutcome.failure("falha ao escrever o workspace do run: " + workspaceWrite.error());
        }

        AgentAdapter.Context adapterContext = new AgentAdapter.Context(
                runContext.companyId(), runContext.agentId(), runContext.runId(), runContext.issueId(), workDir,
                command, env, target, volumeName, null,
                agent.getModel(), agent.getEffort());

        AgentAdapter.StepSink sink = (type, detail) -> store.recordStep(runContext.runId(), type, detail);
        if (replyCollector != null) {
            sink = replyCollector.wrap(sink);
        }

        AgentAdapter.Result result;
        try {
            result = adapter.execute(adapterContext, sink);
        } catch (Exception exception) {
            if (turno != null) {
                turnCoordinator.settleFailed(turno.turnId());
            }
            return RunOutcome.failure("falha no adapter: " + exception.getMessage());
        }

        // Motor (ADR-0014/0006, Q7): roteia os tokens REAIS emitidos pelo adapter para o ledger com
        // costCents=0 (na assinatura o hard-stop de dólares é inócuo). Sucesso E falha: tokens gastos
        // antes de terminar contam — instrumentam o handoff a 50-60% (ADR-0015). Se o adapter LANÇOU
        // (catch acima), não há Result nem emissões: um adapter que queira contabilizar deve devolver
        // Result.fail(error, costEvents), não lançar.
        recordCosts(runContext, result.costEvents());

        // Settle do turno (T.3): a resposta capturada vira a thread; falha marca FAILED (o erro
        // detalhado vive no StepTrace). O handle de sessão é persistido por (Agent, Conversation)
        // — fiação do ADR-0002, --resume segue desligado.
        if (turno != null) {
            if (result.succeeded()) {
                turnCoordinator.settleAnswered(turno.turnId(), replyCollector.text());
            } else {
                turnCoordinator.settleFailed(turno.turnId());
            }
            agentSessions.record(runContext.companyId(), runContext.agentId(), turno.conversationId(),
                    agent.getAdapterType(), result.session(), runContext.runId());
        }

        if (!result.succeeded()) {
            return RunOutcome.failure(result.error());
        }
        return RunOutcome.success();
    }

    /** Cada emissão do adapter vira uma linha no ledger (append-only) com costCents=0 (assinatura). */
    private void recordCosts(RunContext runContext, List<AgentAdapter.CostEmission> emissions) {
        for (AgentAdapter.CostEmission emission : emissions) {
            budget.recordCost(runContext.companyId(), runContext.agentId(), runContext.runId(),
                    runContext.issueId(), emission.provider(), emission.model(), 0L,
                    emission.tokensInput(), emission.tokensOutput());
        }
    }
}
