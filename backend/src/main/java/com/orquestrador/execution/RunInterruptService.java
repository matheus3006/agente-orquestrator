package com.orquestrador.execution;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * F-N4 (ADR-0040, D4): o botao "parar" do v1 — kill controlado + resume nativo (ADR-0038 garante
 * a sessao). Ordem OBRIGATORIA: o CAS RUNNING->INTERRUPTED ({@link HeartbeatRunStore#interrupt})
 * acontece ANTES de tocar o processo — o dispatcher, bloqueado dentro do run sincrono, so alcanca
 * {@code finishFailure} DEPOIS que o processo morre; por essa ordem o CAS deste servico ja tera
 * vencido a corrida por CONSTRUCAO, nunca por timing (prova: {@link RunInterruptTest}).
 *
 * <p>O kill em si e best-effort e vem DEPOIS: falhar em matar o processo (infra fora, ja tinha
 * morrido sozinho) nao desfaz a transicao — o INTENT do dono ja esta gravado, e o pior caso e um
 * processo orfao que o supervisor/reaper trataria de qualquer forma (mesma classe de risco de
 * qualquer kill, nao um risco novo desta fatia).
 */
@Service
public class RunInterruptService {

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunStore store;
    private final AgentRepository agents;
    private final ExecutionTargets targets;

    public RunInterruptService(HeartbeatRunRepository runs, HeartbeatRunStore store,
                               AgentRepository agents, ExecutionTargets targets) {
        this.runs = runs;
        this.store = store;
        this.agents = agents;
        this.targets = targets;
    }

    public InterruptOutcome interrupt(UUID companyId, UUID runId, String reason) {
        HeartbeatRun run = runs.findByIdAndCompanyId(runId, companyId).orElse(null);
        if (run == null) {
            return InterruptOutcome.NOT_FOUND;
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            return InterruptOutcome.NOT_RUNNING;
        }
        // CAS PRIMEIRO (a garantia de nao-corrida): so entao vale a pena matar o processo.
        if (!store.interrupt(runId, run.getLeaseOwner(), reason)) {
            return InterruptOutcome.NOT_RUNNING; // corrida: terminou entre o read acima e o CAS
        }

        Agent agent = agents.findById(run.getAgentId()).orElse(null);
        boolean processoMorto = false;
        if (agent != null) {
            ExecutionTarget target = targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()));
            processoMorto = target.interrupt(runId);
        }
        return InterruptOutcome.interrupted(processoMorto);
    }

    public record InterruptOutcome(Status status, boolean processKilled) {

        public enum Status {
            INTERRUPTED,
            NOT_FOUND,
            NOT_RUNNING
        }

        public static final InterruptOutcome NOT_FOUND = new InterruptOutcome(Status.NOT_FOUND, false);
        public static final InterruptOutcome NOT_RUNNING = new InterruptOutcome(Status.NOT_RUNNING, false);

        public static InterruptOutcome interrupted(boolean processKilled) {
            return new InterruptOutcome(Status.INTERRUPTED, processKilled);
        }
    }
}
