package com.orquestrador.execution;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.execution.lineage.SpawnLineage;
import com.orquestrador.execution.lineage.SpawnLineageGuard;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.OrchestrationAction;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Recebe wakeups e faz COALESCING: garante no maximo 1 Run ativo por Agent.
 * A serializacao por Agent vem do lock pessimista na linha do Agent
 * ({@code findByIdForUpdate}) — multi-no-safe e testavel sem indice parcial.
 */
@Service
public class WakeupService {

    private final AgentRepository agents;
    private final HeartbeatRunRepository runs;
    private final AgentWakeupRequestRepository wakeups;
    private final HeartbeatRunEventRepository events;
    private final TeamService teamService;
    private final SpawnLineageGuard lineageGuard;
    private final TeamFanOutGate fanOutGate;
    private final BudgetService budgets;
    private final RepeatedFailureBreaker breaker;
    private final RepeatedFailureNotice breakerNotice;

    public WakeupService(AgentRepository agents, HeartbeatRunRepository runs,
                         AgentWakeupRequestRepository wakeups, HeartbeatRunEventRepository events,
                         TeamService teamService, SpawnLineageGuard lineageGuard,
                         TeamFanOutGate fanOutGate, BudgetService budgets,
                         RepeatedFailureBreaker breaker, RepeatedFailureNotice breakerNotice) {
        this.agents = agents;
        this.runs = runs;
        this.wakeups = wakeups;
        this.events = events;
        this.teamService = teamService;
        this.lineageGuard = lineageGuard;
        this.fanOutGate = fanOutGate;
        this.budgets = budgets;
        this.breaker = breaker;
        this.breakerNotice = breakerNotice;
    }

    /** Wakeup de ato humano DIRETO (operador/dono): linhagem raiz — nunca fecha ciclo. */
    @Transactional
    public WakeupOutcome enqueue(UUID companyId, UUID agentId, UUID issueId, String reason) {
        return enqueue(companyId, agentId, issueId, reason, SpawnLineage.root());
    }

    /** Wakeup com a cadeia de quem o CAUSOU (T.4): trabalho spawnado por agente carrega a linhagem. */
    @Transactional
    public WakeupOutcome enqueue(UUID companyId, UUID agentId, UUID issueId, String reason,
                                 SpawnLineage lineage) {
        // Lock na linha do Agent: serializa wakeups concorrentes do mesmo Agent.
        Agent agent = agents.findByIdForUpdate(agentId)
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado: " + agentId));
        if (!agent.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Agente nao pertence a company " + companyId);
        }

        // O guard do spawn_lineage (T.4, ADR-0016): acordar ancestral da propria linhagem ou cadeia
        // funda demais = 409 antes de criar qualquer coisa (a face de CHECKOUT cobre o que entra
        // por fora do enqueue).
        lineageGuard.assertWakeupAllowed(lineage, agentId);

        // A trava do arquiteto (ADR-0017, T.2): time sem arquiteto nao dispara trabalho — 409 ANTES
        // de criar wakeup/run (rollback total). A 2a face (preflight do dispatcher) cobre runs que
        // entram por fora do enqueue (promote-on-finalize).
        teamService.assertDispatchAllowed(agent);

        List<HeartbeatRun> active = runs.findByAgentAndStatuses(agentId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING));

        // Circuit-breaker de erro repetido (ADR-0018 teto (c), R18-B): SO o caminho que criaria um
        // Run NOVO (agente ocioso) e SO o wakeup AUTOMATICO (linhagem causada). A mao humana direta
        // (root) fura o breaker — e o gesto que destrava o par (o dono retomou o volante). Barra
        // ANTES de criar o wakeup/cobrar budget: "para so o pedaco travado, o resto continua".
        if (active.isEmpty() && !lineage.isRoot()) {
            var tripped = breaker.avalia(agentId, issueId);
            if (tripped.isPresent()) {
                breakerNotice.avisaSeInedito(companyId, agentId, issueId, tripped.get());
                return WakeupOutcome.barrado();
            }
        }

        AgentWakeupRequest wakeup = wakeups.save(
                new AgentWakeupRequest(UUID.randomUUID(), companyId, agentId, issueId, reason, lineage));

        // O ato de ACORDAR é cobrado na abertura no ledger de orquestração (T.4, anti-enxame),
        // na conta de quem o CAUSOU (último elo da linhagem; nulo = mão humana direta).
        budgets.recordOrchestration(companyId, lineage.causer(), null, OrchestrationAction.WAKEUP);

        if (active.isEmpty()) {
            // O cap de fan-out do time (T.4) conta runs CONCORRENTES: só o caminho que CRIA run
            // novo passa por ele — coalescing/defer reusam o slot ativo do agente.
            fanOutGate.assertFanOutAllowed(agent);
            HeartbeatRun run = runs.save(
                    new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, RunStatus.QUEUED, lineage));
            wakeup.attach(run.getId(), WakeupStatus.COALESCED);
            events.save(new HeartbeatRunEvent(UUID.randomUUID(), run.getId(), companyId, "RUN_QUEUED", reason));
            return new WakeupOutcome(run.getId(), true);
        }

        HeartbeatRun run = active.get(0);
        if (run.getStatus() == RunStatus.QUEUED) {
            // Run ainda nao comecou: mescla. Adota a issue se o Run ainda nao tinha uma.
            if (run.getIssueId() == null && issueId != null) {
                run.setIssueId(issueId);
            }
            wakeup.attach(run.getId(), WakeupStatus.COALESCED);
            return new WakeupOutcome(run.getId(), false);
        }

        // Run em execucao: defer -> sera atendido por um Run seguinte (promote-on-finalize).
        run.setNeedsFollowup(true);
        wakeup.attach(run.getId(), WakeupStatus.DEFERRED);
        return new WakeupOutcome(run.getId(), false);
    }
}
