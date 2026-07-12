package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N4 (ADR-0040, D4): o estado INTERRUPTED e o CAS que o produz — a MESMA disciplina de
 * finalizeRun (guardado por dono) que finishSuccess/finishFailure ja usam. O endpoint de
 * interrupt faz este CAS ANTES de tocar o processo: o dispatcher, bloqueado dentro do run
 * sincrono, so alcanca finishFailure DEPOIS que o processo morre — o CAS ja tera vencido a
 * corrida por construcao (nao por timing).
 */
@SpringBootTest
@ActiveProfiles("test")
class RunInterruptTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository events;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    private UUID runClaimedBy(String owner) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId();
        assertThat(store.claim(runId, owner, Duration.ofMinutes(2))).isTrue();
        return runId;
    }

    @Test
    void interruptDeRunEmExecucaoTransicionaERegistraEvento() {
        UUID runId = runClaimedBy("node-x");

        assertThat(store.interrupt(runId, "node-x", "dono pediu parar")).isTrue();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        List<HeartbeatRunEvent> interrupcoes = events.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(event -> event.getType().equals("RUN_INTERRUPTED"))
                .toList();
        assertThat(interrupcoes).hasSize(1);
        assertThat(interrupcoes.getFirst().getDetail()).isEqualTo("dono pediu parar");
    }

    @Test
    void interruptDeRunQueNaoEstaRodandoDevolveFalseSemMudarStatus() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId(); // ainda QUEUED

        assertThat(store.interrupt(runId, "node-x", "tarde demais")).isFalse();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    /**
     * A prova da race: o CAS do interrupt ganha PRIMEIRO (por construcao — acontece antes de
     * qualquer kill de processo); o finishFailure natural do dispatcher, chegando depois, cai
     * no vazio sob o MESMO guard de finalizeRun que ja protege node-A x node-B no LeaseRenewalTest.
     */
    @Test
    void finishFailureNaturalPosInterruptEhNoOp() {
        UUID runId = runClaimedBy("node-x");
        assertThat(store.interrupt(runId, "node-x", "dono pediu parar")).isTrue();

        store.finishFailure(runId, "node-x", "processo terminou com exit 137");

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(events.findByRunIdOrderByCreatedAtAsc(runId))
                .noneMatch(event -> event.getType().equals("RUN_FAILED"));
    }
}
