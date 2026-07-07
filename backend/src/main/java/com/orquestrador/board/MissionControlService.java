package com.orquestrador.board;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.preview.PreviewArtifactMetadata;
import com.orquestrador.preview.PreviewArtifactRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O agregado do Mission Control (F.0, diferencial #4): o JOIN acontece AQUI, no servidor —
 * 1 poll de 2s devolve a tela inteira, por agente: status derivado, run ativo (com título da
 * Issue) e o rabo do StepTrace. O front nunca faz N+1 de runs/steps/issues.
 *
 * <p>Custo: ~2 consultas por agente ocioso (último run + rabo do trace) sobre índices — roster
 * é um TIME (unidades, não milhares); se um dia virar fleet, este é o único lugar a otimizar.
 */
@Service
public class MissionControlService {

    /** Estados de run que ocupam o agente (invariante: no máximo 1 por agente). */
    private static final EnumSet<RunStatus> ACTIVE_RUN_STATUSES =
            EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING);

    private final AgentRepository agents;
    private final HeartbeatRunRepository runs;
    private final HeartbeatRunEventRepository events;
    private final IssueRepository issues;
    private final PreviewArtifactRepository previews;

    public MissionControlService(AgentRepository agents, HeartbeatRunRepository runs,
                                 HeartbeatRunEventRepository events, IssueRepository issues,
                                 PreviewArtifactRepository previews) {
        this.agents = agents;
        this.runs = runs;
        this.events = events;
        this.issues = issues;
        this.previews = previews;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID companyId) {
        List<Agent> roster = agents.findByCompanyId(companyId).stream()
                .sorted(Comparator.comparing(Agent::getCreatedAt).thenComparing(Agent::getId))
                .toList();

        // Um run ativo por agente (invariante do heartbeat); se uma corrida deixar dois, o mais
        // recente representa o card — o agregado NUNCA estoura por dado inesperado.
        Map<UUID, HeartbeatRun> activeRunByAgent = runs
                .findByCompanyIdAndStatusIn(companyId, ACTIVE_RUN_STATUSES).stream()
                .collect(Collectors.toMap(HeartbeatRun::getAgentId, Function.identity(),
                        (first, second) -> second.getCreatedAt().isAfter(first.getCreatedAt()) ? second : first));

        Set<UUID> agentsWithParkedIssue = issues.findByCompanyIdAndStatus(companyId, IssueStatus.BLOCKED)
                .stream()
                .map(Issue::getAssigneeAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, String> issueTitleById = issueTitlesFor(activeRunByAgent.values());

        // F.9 (ADR-0021 §5): o badge de preview do card — a captura mais recente de cada agente
        // (lista ordenada desc; a primeira ocorrencia por agente vence). Card leve: so metadados,
        // o content de ate 2MB NUNCA viaja no poll de 2s (projecao).
        Map<UUID, LastPreview> lastPreviewByAgent = previews
                .findByCompanyIdOrderByCreatedAtDescVersionDesc(companyId).stream()
                .collect(Collectors.toMap(PreviewArtifactMetadata::getAgentId, LastPreview::from,
                        (newest, older) -> newest));

        List<AgentCard> cards = roster.stream()
                .map(agent -> cardFor(companyId, agent, activeRunByAgent.get(agent.getId()),
                        agentsWithParkedIssue.contains(agent.getId()), issueTitleById,
                        lastPreviewByAgent.get(agent.getId())))
                .toList();
        return new Snapshot(cards);
    }

    private AgentCard cardFor(UUID companyId, Agent agent, HeartbeatRun activeRun,
                              boolean hasParkedIssue, Map<UUID, String> issueTitleById,
                              LastPreview lastPreview) {
        AgentActivityStatus status = deriveStatus(activeRun, hasParkedIssue);
        HeartbeatRun traceSource = activeRun != null ? activeRun
                : runs.findFirstByCompanyIdAndAgentIdOrderByCreatedAtDesc(companyId, agent.getId())
                        .orElse(null);
        List<TraceStep> lastSteps = traceSource == null ? List.of() : traceTailOf(traceSource);
        ActiveRun activeRunView = activeRun == null ? null
                : new ActiveRun(activeRun.getId(), activeRun.getIssueId(),
                        activeRun.getIssueId() != null ? issueTitleById.get(activeRun.getIssueId()) : null,
                        activeRun.getStartedAt(), activeRun.getAttempt());
        return new AgentCard(new AgentSummary(agent.getId(), agent.getName(), agent.getRole()),
                status, activeRunView, lastSteps, lastPreview);
    }

    /** Rodando > na fila > bloqueado-esperando-humano > ocioso. */
    private AgentActivityStatus deriveStatus(HeartbeatRun activeRun, boolean hasParkedIssue) {
        if (activeRun != null && activeRun.getStatus() == RunStatus.RUNNING) {
            return AgentActivityStatus.RUNNING;
        }
        if (activeRun != null) {
            return AgentActivityStatus.QUEUED;
        }
        if (hasParkedIssue) {
            return AgentActivityStatus.BLOCKED;
        }
        return AgentActivityStatus.IDLE;
    }

    /** Os últimos ≤5 steps em ordem cronológica — o run ativo alimenta o mini-terminal; um run
     *  finalizado deixa o rastro (sucesso ou falha) visível no card ocioso/bloqueado. */
    private List<TraceStep> traceTailOf(HeartbeatRun run) {
        List<HeartbeatRunEvent> newestFirst = events.findTop5ByRunIdOrderByCreatedAtDescIdDesc(run.getId());
        return newestFirst.reversed().stream()
                .map(event -> new TraceStep(event.getRunId(), event.getType(), event.getDetail(),
                        event.getCreatedAt()))
                .toList();
    }

    private Map<UUID, String> issueTitlesFor(Collection<HeartbeatRun> activeRuns) {
        Set<UUID> issueIds = activeRuns.stream()
                .map(HeartbeatRun::getIssueId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        return issues.findAllById(issueIds).stream()
                .collect(Collectors.toMap(Issue::getId, Issue::getTitle));
    }

    public record Snapshot(List<AgentCard> agents) {
    }

    public record AgentCard(AgentSummary agent, AgentActivityStatus status, ActiveRun activeRun,
                            List<TraceStep> lastSteps, LastPreview lastPreview) {
    }

    /** O badge "preview" do card (nulo = agente sem captura): clique leva a Issue (zero iframe no grid). */
    public record LastPreview(UUID previewArtifactId, UUID issueId, int version, Instant createdAt) {

        static LastPreview from(PreviewArtifactMetadata artefato) {
            return new LastPreview(artefato.getId(), artefato.getIssueId(), artefato.getVersion(),
                    artefato.getCreatedAt());
        }
    }

    public record AgentSummary(UUID id, String name, String role) {
    }

    public record ActiveRun(UUID id, UUID issueId, String issueTitle, Instant startedAt, int attempt) {
    }

    public record TraceStep(UUID runId, String type, String detail, Instant createdAt) {
    }

    /** O status DERIVADO do card — vocabulário do operador, não o RunStatus cru. */
    public enum AgentActivityStatus {
        RUNNING,
        QUEUED,
        BLOCKED,
        IDLE
    }
}
