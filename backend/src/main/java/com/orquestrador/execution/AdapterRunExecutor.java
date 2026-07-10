package com.orquestrador.execution;

import com.orquestrador.conversation.ConversationTurnCoordinator;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.WorkspaceConvention;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.handoff.ContextHandoffLoop;
import com.orquestrador.handoff.DistillationSkill;
import com.orquestrador.memory.CompanyMemoryMaterializer;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.preview.PreviewArtifactCapturer;
import com.orquestrador.preview.server.PreviewServerManifestRegistrar;
import com.orquestrador.preview.server.PreviewServerSupervisor;
import com.orquestrador.skills.CompanySkillVersion;
import com.orquestrador.skills.SkillMaterializer;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.work.issue.CheckoutResult;
import com.orquestrador.work.issue.IssueService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
    private final DistillationSkill distillationSkill;
    private final ContextHandoffLoop handoffLoop;

    public AdapterRunExecutor(AgentRepository agents, IssueService issues, WorkspaceResolver workspaces,
                              ExecutionTargets targets, AdapterRegistry adapters, HeartbeatRunStore store,
                              SkillSetService skillSets, SkillMaterializer materializer, BudgetService budget,
                              ConversationTurnCoordinator turnCoordinator, AgentSessionStore agentSessions,
                              CompanyMemoryMaterializer memoryMaterializer, PreviewArtifactCapturer previews,
                              PreviewServerManifestRegistrar serverManifests,
                              PreviewServerSupervisor previewServers, DistillationSkill distillationSkill,
                              ContextHandoffLoop handoffLoop) {
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
        this.distillationSkill = distillationSkill;
        this.handoffLoop = handoffLoop;
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

        // ADR-0032 (D3/D4): o adapter resolve ANTES de qualquer preparo — a convenção de workspace
        // dele dirige os materializers, e tipo desconhecido falha AQUI, com a Issue ainda livre
        // (antes do checkout, nenhum lock preso por um run morto).
        AgentAdapter adapter = adapters.forType(agent.getAdapterType());
        WorkspaceConvention convention = adapter.workspaceConvention();

        // Fatia 7a: prepara o workspace ANTES do checkout — materializa a UNIÃO base∪anexos-da-Issue
        // (resolveForRun colapsa por skill com ANEXO-VENCE-A-BASE; anexos de Issue fechada não entram).
        // Uma falha de I/O aqui vira RunOutcome.failure; por ser ANTES do checkout, não deixa a Issue
        // travada por um run morto (o checkout ainda não ocorreu).
        Path workDir = workspaces.resolve(runContext.companyId(), runContext.agentId(), runContext.issueId());
        try {
            // M4 (ADR-0030): o CEO ganha a skill `distillation` (capacidade de plataforma, injetada
            // por-run — zero migração, invisível à biblioteca da company). Demais agentes: set intacto.
            List<CompanySkillVersion> skills = distillationSkill.augment(
                    skillSets.resolveForRun(runContext.companyId(), runContext.agentId(), runContext.issueId()),
                    agent.getRole());
            materializer.materialize(workDir, convention.skillsSubdir(), skills);
            // Faxina permanente (ADR-0032/D6): a árvore de skills das convenções INATIVAS some do
            // host — troca de motor nunca deixa cérebro velho no chão (o .claude/ que o codex nunca
            // leu mas exploraria por busca). No DOCKER a remoção atravessa pela união dos subtrees.
            for (WorkspaceConvention inactive : WorkspaceConvention.KNOWN) {
                if (!inactive.equals(convention)) {
                    deleteRecursively(workDir.resolve(inactive.skillsSubdir()));
                }
            }
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
            // A carta corrente vira o memoryFile da convenção do adapter — .claude/CLAUDE.md no
            // Claude Code, AGENTS.md no codex (ADR-0018/0019/0032): memória durável pelo canal
            // nativo de cada CLI; o prompt carrega só o estado VIVO. Falha de I/O aqui liquida o
            // turno (o claim já ocorreu — FAILED visível > PENDING pendurado).
            try {
                memoryMaterializer.materialize(workDir, convention.memoryFile(), runContext.companyId());
                // Faxina permanente (D6), lado da carta: o memoryFile das convenções INATIVAS some
                // — só em run de TURNO (quem materializou faxina; em run de Issue um AGENTS.md ou
                // CLAUDE.md que o agente escreveu no próprio workspace nunca é tocado).
                for (WorkspaceConvention inactive : WorkspaceConvention.KNOWN) {
                    if (!inactive.equals(convention)) {
                        deleteRecursively(workDir.resolve(inactive.memoryFile()));
                    }
                }
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
        // M6 (ADR-0030, risco #3): NUNCA adicionar `.orq/*` aqui. writeWorkspaceFiles é REPLACE por
        // subtree (membro ausente no host some do volume); materializar `.orq/handoff` apagaria o
        // bilhete entre Runs sob Docker e quebraria o loop de handoff. Guard-rail:
        // WorkspaceWriteOnRunTest.nenhumSubtreeOrqEMaterializado_* FALHA se alguém violar isto.
        // A lista é a UNIÃO das convenções conhecidas (ADR-0032/D6): a ativa atravessa a árvore
        // recém-materializada; as inativas, apagadas do host pela faxina acima, somem do volume
        // pelo próprio replace (rm extra é no-op em volume limpo). Issue: só skills; turno: + carta.
        List<String> materializedSubtrees = new ArrayList<>();
        for (WorkspaceConvention known : WorkspaceConvention.KNOWN) {
            materializedSubtrees.add(known.skillsSubdir());
        }
        if (turno != null) {
            for (WorkspaceConvention known : WorkspaceConvention.KNOWN) {
                materializedSubtrees.add(known.memoryFile());
            }
        }
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
            // M8 (ADR-0030): pós-settle, o loop de handoff. Só em SUCESSO (um turno falho não tem
            // estado coerente a continuar). O handle de sessão já foi gravado acima — a rede
            // resume-cirúrgico (M9) pode lê-lo. Best-effort: o loop nunca reabre o turno liquidado.
            if (result.succeeded()) {
                handoffLoop.afterCeoTurn(new ContextHandoffLoop.FinishedCeoTurn(
                        runContext.companyId(), runContext.agentId(), turno.conversationId(), runContext.runId(),
                        agent.getAdapterType(), agent.getModel(), agent.getEffort(), result.costEvents(),
                        adapter, target, workDir, volumeName));
            }
        }

        if (!result.succeeded()) {
            return RunOutcome.failure(result.error());
        }
        return RunOutcome.success();
    }

    /**
     * O braço da faxina (ADR-0032/D6) no HOST: remove arquivo OU árvore de uma convenção inativa
     * (no LOCAL o workDir É o workspace; no DOCKER a ausência atravessa pelo replace da união).
     * Falha vira UncheckedIOException — cai nos mesmos catches dos materializers (falha LEGÍVEL
     * do run, nunca meia-faxina silenciosa).
     */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("falha na faxina de convencao inativa em " + root, exception);
        }
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
