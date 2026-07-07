package com.orquestrador.execution;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A trava do arquiteto no DISPATCH (ADR-0017: "um time não dispara trabalho sem 1 arquiteto"),
 * nas DUAS faces do funil:
 * 1) ENQUEUE — o wakeup de um agente em time sem arquiteto leva 409 antes de criar run/wakeup;
 * 2) PREFLIGHT — um run QUEUED que entrou por fora do enqueue (promote-on-finalize) é CANCELADO
 *    no dispatcher sem executar (espelha o preflight de budget — defesa em profundidade).
 */
@SpringBootTest
@ActiveProfiles("test")
class DispatchArchitectGateTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamService teamService;

    @Test
    void wakeupDeAgenteEmTimeSemArquitetoLeva409SemCriarRun() {
        UUID companyId = UUID.randomUUID();
        var time = teamService.create(companyId, "Dev", "frontend");
        Agent agente = agents.save(new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null));
        teamService.assignAgent(companyId, agente.getId(), time.getId());

        assertThatThrownBy(() -> wakeups.enqueue(companyId, agente.getId(), null, "trabalhar"))
                .isInstanceOf(ConflictException.class);
        assertThat(runs.findByAgentAndStatuses(agente.getId(),
                java.util.List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING))).isEmpty();

        // com arquiteto definido, o MESMO wakeup passa
        teamService.setArquiteto(companyId, time.getId(), agente.getId());
        assertThat(wakeups.enqueue(companyId, agente.getId(), null, "trabalhar").created()).isTrue();
    }

    @Test
    void runQueEntrouPorForaDoEnqueueEhCanceladoNoPreflightSemExecutar() {
        UUID companyId = UUID.randomUUID();
        var time = teamService.create(companyId, "Dev", "backend");
        Agent agente = agents.save(new Agent(UUID.randomUUID(), companyId, "Nix", "executor", null));
        teamService.assignAgent(companyId, agente.getId(), time.getId());

        // run plantado DIRETO (simula o promote-on-finalize criando por fora do enqueue)
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agente.getId(), null,
                RunStatus.QUEUED)).getId();

        dispatcher.dispatchOnce();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
