package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovery: um Run que ficou RUNNING com lease expirado (worker morto) volta para a fila
 * pelo reaper e e reprocessado ate o fim. Idempotente.
 */
@SpringBootTest
@ActiveProfiles("test")
class HeartbeatReaperTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private LeaseReaper reaper;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runOrfaoVoltaParaFilaEReprocessa() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        // R22-A: agente COM comando — sem ele o run é no-op e a Issue não fecha mais (o DONE
        // fantasma que este teste, sem querer, pinava era o bug #1 do roadmap de ajustes).
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        // simula um worker que pegou o Run e morreu (RUNNING), sem finalizar
        assertThat(store.claim(runId, "ghost-node", Duration.ofMinutes(2))).isTrue();
        // forca o lease para o passado (worker morto)
        jdbc.update("update heartbeat_run set lease_expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(1))), runId);

        // reaper recupera
        assertThat(reaper.reapOnce()).isGreaterThanOrEqualTo(1);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);

        // dispatch reprocessa ate o fim
        dispatcher.dispatchOnce();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
    }
}
