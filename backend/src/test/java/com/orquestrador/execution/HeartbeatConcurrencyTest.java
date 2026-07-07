package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N wakeups concorrentes do mesmo Agent -> no maximo 1 Run ativo (nenhum Run duplicado).
 * O lock na linha do Agent serializa; a invariante "1 Run ativo por Agent" vale sob corrida.
 */
@SpringBootTest
@ActiveProfiles("test")
class HeartbeatConcurrencyTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void wakeupsConcorrentesNaoDuplicamRun() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    wakeups.enqueue(companyId, agentId, null, "w" + idx);
                } catch (Exception ignored) {
                    // um wakeup que perca a corrida do lock nao quebra a invariante
                }
            });
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        List<HeartbeatRun> active = runs.findByAgentAndStatuses(agentId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING));
        assertThat(active).as("no maximo 1 Run ativo por Agent").hasSize(1);
        assertThat(runs.findByCompanyIdOrderByCreatedAtDesc(companyId)).hasSize(1);
    }
}
