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
 * Promote-on-finalize: um wakeup que chega com o Run JA em execucao nao se perde — ao finalizar,
 * o motor cria um Run seguinte para o Agent carregando os wakeups deferidos. Nada some, sem duplicar.
 */
@SpringBootTest
@ActiveProfiles("test")
class HeartbeatPromoteOnFinalizeTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private AgentWakeupRequestRepository wakeupRepo;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void wakeupDuranteORunGeraRunSeguinte() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        // Run 1 fica em execucao
        UUID run1 = wakeups.enqueue(companyId, agentId, null, "primeiro").runId();
        assertThat(store.claim(run1, "node-x", Duration.ofMinutes(2))).isTrue();

        // chega um wakeup com o Run rodando -> deferido
        WakeupOutcome deferred = wakeups.enqueue(companyId, agentId, null, "durante-o-run");
        assertThat(deferred.created()).isFalse();
        assertThat(deferred.runId()).isEqualTo(run1);

        // finaliza o Run 1 -> promove um Run 2
        store.finishSuccess(run1, "node-x");

        assertThat(runs.findById(run1).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        List<HeartbeatRun> active = runs.findByAgentAndStatuses(agentId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING));
        assertThat(active).hasSize(1);
        UUID run2 = active.get(0).getId();
        assertThat(run2).isNotEqualTo(run1);

        // o wakeup deferido foi religado no Run 2 como COALESCED
        assertThat(wakeupRepo.findByRunIdAndStatus(run2, WakeupStatus.COALESCED)).hasSize(1);
    }
}
