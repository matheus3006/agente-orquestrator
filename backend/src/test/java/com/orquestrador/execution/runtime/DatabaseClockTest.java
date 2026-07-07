package com.orquestrador.execution.runtime;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.12 (ADR-0026): a comparação de retenção usa SEMPRE o relógio do BANCO (nunca o clock do
 * processo — defesa contra clock skew e reset no restart). O invariante que o {@code WorkspaceReaper}
 * depende NÃO é "clock.now() == wall-clock UTC" (o H2 do teste devolve now() zone-naive), e sim que
 * {@link DatabaseClock#now()} e a âncora {@code issue.updated_at} — AMBOS escritos por {@code now()}
 * do banco — vivam no MESMO frame, para o offset cancelar na subtração da retenção.
 */
@SpringBootTest
@ActiveProfiles("test")
class DatabaseClockTest {

    @Autowired
    private DatabaseClock clock;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepository;

    @Test
    void relogioDoBancoEUpdatedAtVivemNoMesmoFrame() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "issue", null).getId();

        // checkout (transacional) carimba updated_at = now() do BANCO (a âncora terminal do reaper).
        issues.checkout(companyId, issueId, agentId, UUID.randomUUID());
        Instant carimboDoBanco = issueRepository.findById(issueId).map(Issue::getUpdatedAt).orElseThrow();

        Instant agoraDoBanco = clock.now();

        // Mesmo frame: o clock (lido DEPOIS) nunca é anterior ao carimbo, e a distância é pequena —
        // não os ~3h de um descasamento de fuso. É esse invariante que faz a retenção comparar certo.
        assertThat(Duration.between(carimboDoBanco, agoraDoBanco))
                .isBetween(Duration.ofSeconds(-1), Duration.ofMinutes(5));
    }
}
