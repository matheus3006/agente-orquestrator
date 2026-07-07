package com.orquestrador.work.issue;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prova central da fatia 1: sob N agentes pegando a MESMA issue ao mesmo tempo,
 * EXATAMENTE UM ganha. O banco arbitra a corrida; zero double-work. A invariante
 * vale para qualquer interleaving — um perdedor ve 0 linhas ou toma excecao de
 * concorrencia; ambos contam como "nao pegou".
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueCheckoutConcurrencyTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void exatamenteUmAgenteGanhaACorrida() throws Exception {
        UUID companyId = companies.create("C", "race-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        int n = 8;
        List<UUID> agentIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            agentIds.add(agents.save(new Agent(UUID.randomUUID(), companyId, "A" + i, "worker", null)).getId());
        }
        UUID issueId = issues.create(companyId, "corrida", null).getId();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final UUID agentId = agentIds.get(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return issues.checkout(companyId, issueId, agentId, null).acquired();
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        start.countDown();

        int acquired = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get())) {
                acquired++;
            }
        }
        pool.shutdown();

        assertThat(acquired).as("exatamente um agente pega a issue").isEqualTo(1);
        Issue issue = issues.get(companyId, issueId);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(agentIds).contains(issue.getAssigneeAgentId());
    }
}
