package com.orquestrador.work.issue;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
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
    @Autowired
    private HeartbeatRunRepository runs;

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
        // Cada disputante chega com o PRÓPRIO Run real desta issue — é o que a cerca de
        // procedência exige (Fatia I-ter), e é o que 8 agentes disputando de verdade teriam.
        // Sem a linha, os 8 tomariam 403 e a corrida teria zero vencedores em vez de um.
        List<UUID> runIds = agentIds.stream()
                .map(agentId -> runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId,
                        issueId, RunStatus.RUNNING)).getId())
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final UUID agentId = agentIds.get(i);
            final UUID runId = runIds.get(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return issues.checkout(companyId, issueId, agentId, runId).acquired();
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
