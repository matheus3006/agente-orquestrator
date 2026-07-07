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

import static org.assertj.core.api.Assertions.assertThat;

/** 5 wakeups do mesmo Agent -> 1 unico Run (coalescing), nao 5. */
@SpringBootTest
@ActiveProfiles("test")
class HeartbeatCoalescingTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private AgentWakeupRequestRepository wakeupRepo;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void wakeupsRedundantesCoalescemNumUnicoRun() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        WakeupOutcome first = wakeups.enqueue(companyId, agentId, null, "w1");
        assertThat(first.created()).isTrue();
        for (int i = 2; i <= 5; i++) {
            WakeupOutcome o = wakeups.enqueue(companyId, agentId, null, "w" + i);
            assertThat(o.created()).isFalse();
            assertThat(o.runId()).isEqualTo(first.runId());
        }

        List<HeartbeatRun> active = runs.findByAgentAndStatuses(agentId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING));
        assertThat(active).hasSize(1);
        assertThat(wakeupRepo.findByRunId(first.runId())).hasSize(5);
    }
}
