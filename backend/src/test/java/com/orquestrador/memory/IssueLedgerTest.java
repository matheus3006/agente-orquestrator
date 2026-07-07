package com.orquestrador.memory;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O Ledger por-Issue (ADR-0019/D3): o report é a saída final do run concluinte — read-model
 * derivado (nada duplicado), datado, ausente enquanto nenhum run concluiu.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueLedgerTest {

    @Autowired
    private IssueLedger ledger;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void reportDaIssueEASaidaFinalDoRunConcluinte() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent worker = new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null);
        worker.configureRuntime("process",
                "printf '%s\\n' 'PLANO: landing' 'RESULTADO: pagina no ar'", "LOCAL");
        UUID workerId = agents.save(worker).getId();
        UUID issueId = issues.create(companyId, "Landing page", null).getId();

        // Antes de qualquer run concluído: sem report (o Ledger não inventa).
        assertThat(ledger.reportFor(companyId, issueId)).isEmpty();

        UUID runId = wakeups.enqueue(companyId, workerId, issueId, "trabalhar").runId();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        IssueLedger.IssueReport report = ledger.reportFor(companyId, issueId).orElseThrow();
        assertThat(report.runId()).isEqualTo(runId);
        assertThat(report.report()).contains("PLANO: landing");
        assertThat(report.report()).contains("RESULTADO: pagina no ar");
        assertThat(report.concludedAt()).isNotNull(); // capado/DATADO (D3)

        // Issue de outra company não vaza.
        UUID outraCompany = companies.create("D", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        assertThat(ledger.reportFor(outraCompany, issueId)).isEmpty();
    }
}
