package com.orquestrador.execution;

import com.orquestrador.execution.lineage.SpawnLineageGuard;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.TeamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Orquestra o ciclo de cada Run sem segurar transacao longa: preflights (budget + arquiteto) ->
 * claim (CAS) -> executar (fora da transacao do claim) -> finalizar. O claim CAS garante que cada
 * Run e processado por um unico worker, mesmo com varios dispatchers/nos concorrentes.
 */
@Service
public class HeartbeatDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatDispatcher.class);

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunStore store;
    private final RunExecutor executor;
    private final BudgetService budgets;
    private final TeamService teamService;
    private final AgentRepository agents;
    private final SpawnLineageGuard lineageGuard;
    private final TeamFanOutGate fanOutGate;
    private final LeaseRenewer leaseRenewer;

    private final String nodeId = "node-" + UUID.randomUUID();
    private final Duration leaseDuration = Duration.ofMinutes(2);

    public HeartbeatDispatcher(HeartbeatRunRepository runs, HeartbeatRunStore store,
                               RunExecutor executor, BudgetService budgets,
                               TeamService teamService, AgentRepository agents,
                               SpawnLineageGuard lineageGuard, TeamFanOutGate fanOutGate,
                               LeaseRenewer leaseRenewer) {
        this.runs = runs;
        this.store = store;
        this.executor = executor;
        this.budgets = budgets;
        this.teamService = teamService;
        this.agents = agents;
        this.lineageGuard = lineageGuard;
        this.fanOutGate = fanOutGate;
        this.leaseRenewer = leaseRenewer;
    }

    /** Processa todos os Runs QUEUED visiveis nesta passada. Retorna quantos foram executados. */
    public int dispatchOnce() {
        int dispatched = 0;
        for (HeartbeatRun run : runs.findTop50ByStatusOrderByCreatedAtAsc(RunStatus.QUEUED)) {
            // O teto de 5s do `DispatchWorker.close()` é o orçamento de UMA derrubada; sem esta
            // linha ele era dividido por até 50 iteracoes, porque o laco seguia reivindicando runs
            // NOVOS depois do `shutdownNow` — cada um subindo container e escalando o proprio
            // `docker rm -f`. `isInterrupted()`, nunca `Thread.interrupted()`: consumir a flag aqui
            // mataria a escalacao la embaixo (ADR-0050 §A escalacao de morte tem de COMPLETAR).
            if (Thread.currentThread().isInterrupted()) {
                log.info("dispatch interrompido (shutdown em curso): {} run(s) despachado(s) nesta"
                        + " passada, o resto da fila fica QUEUED para o proximo boot", dispatched);
                break;
            }
            UUID runId = run.getId();

            // PREFLIGHT de budget: escopo pausado -> cancela o Run em vez de executar.
            if (budgets.invocationBlocked(run.getCompanyId(), run.getAgentId())) {
                store.cancel(runId, "budget hard-stop");
                continue;
            }

            // PREFLIGHT do arquiteto (ADR-0017, T.2): a 2a face da trava — pega runs que entraram
            // por fora do enqueue (promote-on-finalize) ou cujo time perdeu o arquiteto no meio.
            var agente = agents.findById(run.getAgentId()).orElse(null);
            if (agente != null && teamService.dispatchBlocked(agente)) {
                store.cancel(runId, "time sem arquiteto (ADR-0017)");
                continue;
            }

            // PREFLIGHT do fan-out (T.4): com o em-voo do time ja no cap, o run que entrou por
            // fora do enqueue e cancelado — a 2a face do cap por arquiteto.
            if (agente != null && fanOutGate.fanOutBlocked(agente)) {
                store.cancel(runId, "fan-out do arquiteto no teto (ADR-0016)");
                continue;
            }

            // PREFLIGHT do spawn_lineage (T.4): o CHECKOUT recusa run que nasceu ilegal por fora
            // do enqueue (loop materializado / cadeia funda demais) — a rede sob o CEO com maos.
            String linhagemIlegal = lineageGuard.checkoutBlockReason(run).orElse(null);
            if (linhagemIlegal != null) {
                store.cancel(runId, linhagemIlegal);
                continue;
            }

            if (!store.claim(runId, nodeId, leaseDuration)) {
                continue; // outro worker pegou
            }

            // Pulso de vida (ADR-0005): nasce no claim, morre AQUI — o close cobre executar E
            // finalizar, para o timer nao vazar nem quando o proprio finalize lanca.
            try (LeaseRenewer.Pulse pulse = leaseRenewer.start(runId, nodeId, leaseDuration)) {
                RunOutcome outcome;
                try {
                    RunContext runContext = store.contextFor(runId)
                            .orElseThrow(() -> new IllegalStateException("Run sumiu apos claim: " + runId));
                    outcome = executor.execute(runContext);
                } catch (Exception exception) {
                    outcome = RunOutcome.failure(exception.getMessage());
                }

                if (outcome.succeeded()) {
                    store.finishSuccess(runId, nodeId);
                } else {
                    store.finishFailure(runId, nodeId, outcome.error());
                }
            }
            dispatched++;
        }
        return dispatched;
    }

    public String nodeId() {
        return nodeId;
    }
}
