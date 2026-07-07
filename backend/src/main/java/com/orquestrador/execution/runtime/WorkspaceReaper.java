package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O SEGUNDO reaper da casa (F.12/ADR-0026): coleta o workspace por-Issue de Issues TERMINAIS
 * (DONE/CANCELLED) com retenção vencida, nos dois alvos, com auditoria durável. Par do
 * {@code PreviewServerSupervisor.reapOnce()}/{@code LeaseReaper}: {@link #sweepOnce()} público
 * agendado pelo {@code HeartbeatScheduler}; {@link #sweepTarget} é o núcleo profundo testável.
 *
 * <p><b>World-driven por DERIVAÇÃO</b> (a filosofia provada do {@code PreviewServerSupervisor}:
 * fonte da verdade é o MUNDO): 1×/ciclo lista o mundo do alvo e, para cada Issue que já rodou,
 * DERIVA o ref esperado pelo {@link WorkspaceResolver} e intersecta — zero parsing de nome, zero
 * estado especular, zero migração (o nome usa UUID encurtado, a inversão nome→banco é impossível).
 * Um ref no mundo fora de TODA derivação é ÓRFÃO: só WARN, nunca deleção às cegas.
 *
 * <p><b>Retenção</b> sempre contra o relógio do BANCO ({@link DatabaseClock}) sobre a âncora
 * durável {@code issue.updated_at} (a transição terminal). <b>Re-read fresco</b> do status
 * imediatamente antes de CADA remoção fecha a race com REOPEN/checkout. Um candidato em-uso/falho
 * vira WARN + skip — o sweep nunca aborta pelos demais.
 */
@Component
public class WorkspaceReaper {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceReaper.class);

    private final IssueRepository issues;
    private final ExecutionTargets targets;
    private final WorkspaceResolver workspaces;
    private final ActivityLogService activityLog;
    private final DatabaseClock clock;
    private final boolean enabled;
    private final Duration retention;

    public WorkspaceReaper(IssueRepository issues, ExecutionTargets targets, WorkspaceResolver workspaces,
                           ActivityLogService activityLog, DatabaseClock clock,
                           @Value("${orquestrador.workspace.gc.enabled:true}") boolean enabled,
                           @Value("${orquestrador.workspace.gc.retention-days:7}") long retentionDays) {
        this.issues = issues;
        this.targets = targets;
        this.workspaces = workspaces;
        this.activityLog = activityLog;
        this.clock = clock;
        this.enabled = enabled;
        this.retention = Duration.ofDays(retentionDays);
    }

    /** O que UM ciclo fez num alvo — devolvido para o teste e resumido no log; efeitos já aplicados. */
    public record SweepReport(List<String> collected, List<String> skipped, List<String> orphans) {
    }

    /**
     * O ciclo agendado: kill-switch e varredura dos dois alvos. Desligado
     * ({@code orquestrador.workspace.gc.enabled=false}) NÃO coleta nada. Falha de um alvo (ex.:
     * daemon fora) vira WARN e não impede o outro.
     */
    public void sweepOnce() {
        if (!enabled) {
            return;
        }
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            try {
                SweepReport report = sweepTarget(targets.forType(type));
                if (!report.collected().isEmpty() || !report.orphans().isEmpty()
                        || !report.skipped().isEmpty()) {
                    log.info("GC {}: {} coletado(s), {} pulado(s), {} orfao(s)", type,
                            report.collected().size(), report.skipped().size(), report.orphans().size());
                }
            } catch (RuntimeException exception) {
                log.warn("sweep de GC do alvo {} falhou: {}", type, exception.getMessage());
            }
        }
    }

    /**
     * O núcleo testável: lista o mundo do alvo, deriva o ref esperado de cada Issue com workspace,
     * intersecta, e para cada match terminal-vencido remove (re-read fresco antes) e audita. Refs
     * do mundo fora de toda derivação viram WARN de órfão. Nunca dentro de transação de banco (a
     * remoção é filesystem/docker).
     */
    SweepReport sweepTarget(ExecutionTarget target) {
        List<String> collected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> orphans = new ArrayList<>();

        List<String> world = target.listIssueWorkspaces(workspaces);
        if (world.isEmpty()) {
            return new SweepReport(collected, skipped, orphans);
        }

        Instant cutoff = clock.now().minus(retention);

        // DERIVAÇÃO: o ref esperado de cada Issue que já rodou -> a Issue. Refs no mundo sem entrada
        // aqui são órfãos (bug de derivação nosso, não alvo de deleção).
        Map<String, Issue> byRef = new HashMap<>();
        for (Issue issue : issues.findByAssigneeAgentIdIsNotNull()) {
            byRef.put(workspaces.refFor(target.type(), issue.getCompanyId(),
                    issue.getAssigneeAgentId(), issue.getId()), issue);
        }

        for (String ref : world) {
            Issue issue = byRef.get(ref);
            if (issue == null) {
                log.warn("workspace orfao no alvo {}: {} nao deriva de nenhuma Issue — NAO coletado (WARN)",
                        target.type(), ref);
                orphans.add(ref);
                continue;
            }
            if (!collectible(issue, cutoff)) {
                continue; // vivo (não-terminal) ou ainda dentro da retenção: deixa
            }
            // Re-read fresco imediatamente antes da remoção (o status pode ter mudado no meio-tempo).
            Issue fresh = issues.findById(issue.getId()).orElse(null);
            if (fresh == null || !collectible(fresh, cutoff)) {
                continue;
            }
            ExecutionTarget.IssueWorkspaceRemoval outcome = target.removeIssueWorkspace(
                    workspaces, fresh.getCompanyId(), fresh.getAssigneeAgentId(), fresh.getId());
            switch (outcome) {
                case REMOVED -> {
                    recordCollected(fresh, target.type(), ref);
                    collected.add(ref);
                    log.info("workspace {} coletado (Issue {} terminal vencida, alvo {})",
                            ref, fresh.getId(), target.type());
                }
                case IN_USE -> {
                    log.warn("workspace {} em uso — skip; o proximo ciclo tenta de novo", ref);
                    skipped.add(ref);
                }
                case FAILED -> skipped.add(ref); // o alvo já logou o motivo legível
                case ABSENT -> {
                    // sumiu entre listar e remover: no-op, sem auditar um "coletado" falso
                }
            }
        }
        return new SweepReport(collected, skipped, orphans);
    }

    private boolean collectible(Issue issue, Instant cutoff) {
        return isTerminal(issue.getStatus()) && issue.getUpdatedAt().isBefore(cutoff);
    }

    private void recordCollected(Issue issue, ExecutionTarget.Type type, String ref) {
        // Auditoria APÓS a remoção REAL (só REMOVED audita). Falha aqui não desfaz a remoção —
        // registra em ERROR (raro) para investigação, sem derrubar o sweep dos demais.
        try {
            activityLog.record(issue.getCompanyId(), ActorType.SYSTEM, null,
                    ActivityAction.WORKSPACE_COLLECTED, "issue", issue.getId(),
                    Map.of("target", type.name(), "workspaceRef", ref));
        } catch (RuntimeException exception) {
            log.error("workspace {} removido mas a auditoria WORKSPACE_COLLECTED falhou (Issue {}): {}",
                    ref, issue.getId(), exception.getMessage());
        }
    }

    private static boolean isTerminal(IssueStatus status) {
        return status == IssueStatus.DONE || status == IssueStatus.CANCELLED;
    }
}
