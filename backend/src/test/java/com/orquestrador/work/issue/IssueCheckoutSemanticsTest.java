package com.orquestrador.work.issue;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantica deterministica do checkout atomico (sem threads): cada ramo do WHERE guardado.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueCheckoutSemanticsTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    private UUID companyId;
    private UUID agentA;
    private UUID agentB;

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newAgent(UUID company, String name) {
        return agents.save(new Agent(UUID.randomUUID(), company, name, "worker", null)).getId();
    }

    @BeforeEach
    void setup() {
        companyId = companies.create("C", "c-" + rand(), UUID.randomUUID()).getId();
        agentA = newAgent(companyId, "A");
        agentB = newAgent(companyId, "B");
    }

    @Test
    void cicloDeVidaDoCheckout() {
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        // A pega a issue OPEN
        CheckoutResult r1 = issues.checkout(companyId, issueId, agentA, null);
        assertThat(r1.acquired()).isTrue();
        assertThat(r1.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(r1.issue().getAssigneeAgentId()).isEqualTo(agentA);
        UUID run = r1.runId();

        // B nao pega (409): assignee ja e A
        assertThat(issues.checkout(companyId, issueId, agentB, null).acquired()).isFalse();

        // A reentra com o MESMO run -> idempotente
        assertThat(issues.checkout(companyId, issueId, agentA, run).acquired()).isTrue();

        // A com run NOVO nao rouba a propria trava ativa
        assertThat(issues.checkout(companyId, issueId, agentA, UUID.randomUUID()).acquired()).isFalse();

        // completar com run errado falha
        assertThat(issues.complete(companyId, issueId, UUID.randomUUID())).isFalse();

        // completar com o run detentor -> DONE e trava liberada
        assertThat(issues.complete(companyId, issueId, run)).isTrue();
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);

        // apos DONE, ninguem pega (status fora de OPEN/IN_PROGRESS)
        assertThat(issues.checkout(companyId, issueId, agentB, null).acquired()).isFalse();
    }

    @Test
    void checkoutComCompanyErradaNaoPega() {
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID otraCompany = companies.create("X", "x-" + rand(), UUID.randomUUID()).getId();
        UUID otroAgente = newAgent(otraCompany, "Z");

        // company_id no WHERE nao casa -> 0 linhas
        assertThat(issues.checkout(otraCompany, issueId, otroAgente, null).acquired()).isFalse();
    }
}
