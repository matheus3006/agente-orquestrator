package com.orquestrador.governance.budget;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Ledger = verdade: o gasto e SUM(cost_event), por agente e por company. */
@SpringBootTest
@ActiveProfiles("test")
class BudgetLedgerTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void gastoEhSomaDoLedger() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentA = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID agentB = agents.save(new Agent(UUID.randomUUID(), companyId, "B", "worker", null)).getId();

        budgets.recordCost(companyId, agentA, null, null, "openai", "gpt", 30, 0, 0);
        budgets.recordCost(companyId, agentA, null, null, "openai", "gpt", 70, 0, 0);
        budgets.recordCost(companyId, agentB, null, null, "openai", "gpt", 25, 0, 0);

        assertThat(budgets.spentForAgent(agentA)).isEqualTo(100);
        assertThat(budgets.spentForAgent(agentB)).isEqualTo(25);
        assertThat(budgets.spentForCompany(companyId)).isEqualTo(125);
        // sem policy, nada bloqueia
        assertThat(budgets.invocationBlocked(companyId, agentA)).isFalse();
    }
}
