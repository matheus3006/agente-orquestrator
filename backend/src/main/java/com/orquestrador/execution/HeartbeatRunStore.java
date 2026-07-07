package com.orquestrador.execution;

import com.orquestrador.execution.lineage.SpawnLineage;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.OrchestrationAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocos transacionais do motor (cada metodo = 1 transacao curta). Mantidos num bean separado
 * para o {@link HeartbeatDispatcher} (orquestracao, sem transacao longa) poder compor claim ->
 * executar -> finalizar sem segurar lock durante a execucao e sem self-invocation de @Transactional.
 */
@Service
public class HeartbeatRunStore {

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunEventRepository events;
    private final AgentWakeupRequestRepository wakeups;
    private final BudgetService budgets;

    public HeartbeatRunStore(HeartbeatRunRepository runs, HeartbeatRunEventRepository events,
                             AgentWakeupRequestRepository wakeups, BudgetService budgets) {
        this.runs = runs;
        this.events = events;
        this.wakeups = wakeups;
        this.budgets = budgets;
    }

    /** Claim por CAS: retorna true se este worker pegou o Run (QUEUED -> RUNNING + lease). */
    @Transactional
    public boolean claim(UUID runId, String owner, Duration lease) {
        Instant now = Instant.now();
        int rows = runs.claim(runId, RunStatus.QUEUED, RunStatus.RUNNING, owner, now.plus(lease), now);
        if (rows == 1) {
            recordEvent(runId, "RUN_STARTED", null);
            HeartbeatRun run = runs.findById(runId).orElseThrow();
            // RUN_OPEN e cobrado na ABERTURA do trabalho de verdade (T.4) — so no 1o claim:
            // a re-execucao idempotente do reaper (attempt > 1) e o MESMO ato, nao cobra de novo.
            if (run.getAttempt() == 1) {
                budgets.recordOrchestration(run.getCompanyId(),
                        SpawnLineage.decode(run.getSpawnLineage()).causer(), runId,
                        OrchestrationAction.RUN_OPEN);
            }
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<RunContext> contextFor(UUID runId) {
        return runs.findById(runId)
                .map(run -> new RunContext(run.getCompanyId(), run.getAgentId(), run.getId(), run.getIssueId()));
    }

    @Transactional
    public void finishSuccess(UUID runId, String owner) {
        if (runs.finalizeRun(runId, RunStatus.RUNNING, RunStatus.SUCCEEDED, owner, Instant.now(), null) == 1) {
            recordEvent(runId, "RUN_SUCCEEDED", null);
            promoteIfNeeded(runId);
        }
    }

    @Transactional
    public void finishFailure(UUID runId, String owner, String error) {
        if (runs.finalizeRun(runId, RunStatus.RUNNING, RunStatus.FAILED, owner, Instant.now(), error) == 1) {
            recordEvent(runId, "RUN_FAILED", error);
            promoteIfNeeded(runId);
        }
    }

    /** Reaper: Runs com lease expirado voltam para QUEUED (idempotente). Retorna quantos. */
    @Transactional
    public int reapExpired() {
        return runs.requeueExpired(RunStatus.RUNNING, RunStatus.QUEUED, Instant.now());
    }

    /**
     * Promote-on-finalize: se chegaram wakeups enquanto o Run rodava (needs_followup),
     * cria um novo Run QUEUED para o mesmo Agent e religa os wakeups DEFERRED nele.
     * O Run promovido herda a linhagem MAIS FUNDA dos deferidos (T.4): promover não pode
     * lavar a cadeia de causadores — o checkout ainda recusa se ela for ilegal.
     */
    private void promoteIfNeeded(UUID runId) {
        HeartbeatRun run = runs.findById(runId).orElseThrow();
        if (!run.isNeedsFollowup()) {
            return;
        }
        List<AgentWakeupRequest> deferred = wakeups.findByRunIdAndStatus(runId, WakeupStatus.DEFERRED);
        UUID issueId = deferred.stream()
                .map(AgentWakeupRequest::getIssueId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(run.getIssueId());
        SpawnLineage deepestLineage = deferred.stream()
                .map(wakeup -> SpawnLineage.decode(wakeup.getSpawnLineage()))
                .max(java.util.Comparator.comparingInt(SpawnLineage::depth))
                .orElse(SpawnLineage.root());
        HeartbeatRun next = runs.save(new HeartbeatRun(UUID.randomUUID(), run.getCompanyId(),
                run.getAgentId(), issueId, RunStatus.QUEUED, deepestLineage));
        for (AgentWakeupRequest wakeup : deferred) {
            wakeup.attach(next.getId(), WakeupStatus.COALESCED);
        }
        run.setNeedsFollowup(false);
        recordEvent(next.getId(), "RUN_QUEUED", "promote-on-finalize");
    }

    /** Registra um passo de StepTrace de um Run (usado pelo executor ao capturar stdout do adapter). */
    @Transactional
    public void recordStep(UUID runId, String type, String detail) {
        recordEvent(runId, type, detail);
    }

    /** Cancela um Run ainda QUEUED (preflight de budget). */
    @Transactional
    public void cancel(UUID runId, String reason) {
        if (runs.cancelIfStatus(runId, RunStatus.QUEUED, RunStatus.CANCELLED) == 1) {
            recordEvent(runId, "RUN_CANCELLED", reason);
        }
    }

    private void recordEvent(UUID runId, String type, String detail) {
        HeartbeatRun run = runs.findById(runId).orElseThrow();
        events.save(new HeartbeatRunEvent(UUID.randomUUID(), runId, run.getCompanyId(), type, detail));
    }
}
