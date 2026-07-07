package com.orquestrador.execution;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O CAP DE FAN-OUT por arquiteto (T.4; ADR-0016 rev. 2026-07-04): o time do arquiteto não espalha
 * mais trabalho concorrente que o cap. Duas faces — espelha a trava do arquiteto de T.2:
 * 1) ENQUEUE — wakeup que CRIARIA um run além do cap leva 409 (coalescing no run já ativo do
 *    mesmo agente NÃO conta fan-out novo e segue passando);
 * 2) PREFLIGHT — run que entrou por fora do enqueue é CANCELADO quando o em-voo do time já
 *    ocupa o cap.
 */
@SpringBootTest
@ActiveProfiles("test")
class DispatchFanOutCapTest {

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
    @Autowired
    private TeamRepository teams;

    @AfterEach
    void drenaAFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // a classe enfileira sem despachar — o contexto H2 é compartilhado entre classes
        }
    }

    private Team timeComArquitetoECap(UUID companyId, Agent arquiteto, int fanOutCap) {
        Team time = teamService.create(companyId, "Dev", "cap-" + UUID.randomUUID().toString().substring(0, 8));
        teamService.assignAgent(companyId, arquiteto.getId(), time.getId());
        teamService.setArquiteto(companyId, time.getId(), arquiteto.getId());
        Team persistido = teams.findById(time.getId()).orElseThrow();
        persistido.definirFanOutCap(fanOutCap);
        return teams.save(persistido);
    }

    @Test
    void enqueueQueCriariaRunAlemDoCapDoTimeLeva409() {
        UUID companyId = UUID.randomUUID();
        Agent arquiteto = agents.save(new Agent(UUID.randomUUID(), companyId, "Arq", "arquiteto", null));
        Team time = timeComArquitetoECap(companyId, arquiteto, 1);
        Agent colega = agents.save(new Agent(UUID.randomUUID(), companyId, "Beta", "executor", null));
        teamService.assignAgent(companyId, colega.getId(), time.getId());

        // 1º wakeup ocupa o único slot do time
        assertThat(wakeups.enqueue(companyId, arquiteto.getId(), null, "planejar").created()).isTrue();

        // 2º agente do MESMO time criaria o 2º run concorrente → 409 sem criar nada
        assertThatThrownBy(() -> wakeups.enqueue(companyId, colega.getId(), null, "executar"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("fan-out");
        assertThat(runs.findByAgentAndStatuses(colega.getId(),
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING))).isEmpty();

        // re-acordar quem JÁ ocupa o slot coalescé — não é fan-out novo
        assertThat(wakeups.enqueue(companyId, arquiteto.getId(), null, "de novo").created()).isFalse();
    }

    @Test
    void runAlemDoCapQueEntrouPorForaEhCanceladoNoPreflight() {
        UUID companyId = UUID.randomUUID();
        Agent arquiteto = agents.save(new Agent(UUID.randomUUID(), companyId, "Arq", "arquiteto", null));
        Team time = timeComArquitetoECap(companyId, arquiteto, 1);
        Agent colega = agents.save(new Agent(UUID.randomUUID(), companyId, "Gama", "executor", null));
        teamService.assignAgent(companyId, colega.getId(), time.getId());

        // em-voo do time ocupa o cap (RUNNING plantado; fica órfão de lease — inócuo p/ o resto)
        runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, arquiteto.getId(), null, RunStatus.RUNNING));
        // run QUEUED que entrou por fora do enqueue (ex.: promote-on-finalize)
        UUID porFora = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, colega.getId(), null,
                RunStatus.QUEUED)).getId();

        dispatcher.dispatchOnce();

        assertThat(runs.findById(porFora).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
