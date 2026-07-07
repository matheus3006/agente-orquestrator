package com.orquestrador.work.issue;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O limiar do gate é config global viva ({@code orquestrador.review.risk-threshold}, default 5 —
 * ADR-0012), não um 5 hardcoded: com limiar 3, risco 3 estaciona e risco 2 conclui.
 */
@SpringBootTest(properties = "orquestrador.review.risk-threshold=3")
@ActiveProfiles("test")
class ReviewThresholdConfigTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private IssueSettlement settleSuccessWithRisk(UUID companyId, int riskLevel) {
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa", null, riskLevel).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        return issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success());
    }

    @Test
    void riscoNoLimiarConfiguradoEstaciona() {
        assertThat(settleSuccessWithRisk(newCompany(), 3)).isEqualTo(IssueSettlement.SUBMITTED_FOR_REVIEW);
    }

    @Test
    void riscoAbaixoDoLimiarConfiguradoConclui() {
        assertThat(settleSuccessWithRisk(newCompany(), 2)).isEqualTo(IssueSettlement.COMPLETED);
    }
}
