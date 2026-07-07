package com.orquestrador.skills;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecha a corrida TOCTOU do re-anexo (achado da revisão adversarial): N boards anexam a MESMA skill/versão
 * à MESMA Issue ao mesmo tempo. O pré-check resolve o caso sequencial; sob concorrência, a unique
 * (issue_id, company_skill_version_id) arbitra — EXATAMENTE UM registro nasce e os perdedores tomam um
 * {@link ConflictException} (409) limpo, nunca um 500 (DataIntegrityViolationException vazando).
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueSkillAttachmentConcurrencyTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private CompanySkillRepository companySkills;
    @Autowired
    private IssueSkillAttachmentRepository attachments;

    @TempDir
    Path skillsRoot;

    @Test
    void anexosConcorrentesIdenticosGeramUmRegistroEPerdedoresRecebem409() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID board = UUID.randomUUID();
        UUID agentId = agents.save(with(companyId)).getId();

        Path dir = Files.createDirectories(skillsRoot.resolve("race-skill"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: race-skill\ndescription: d\n---\n# corpo\n");
        indexer.index(companyId, skillsRoot);
        UUID skillId = companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0).getSkillId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<Throwable>> outcomes = new ArrayList<>();
        for (int racerIndex = 0; racerIndex < racers; racerIndex++) {
            outcomes.add(pool.submit(() -> {
                startLine.await();
                try {
                    skillSets.attachToIssue(companyId, issueId, agentId, skillId, ActorType.BOARD, board, null);
                    return null; // sucesso: inseriu ou devolveu o existente (idempotente)
                } catch (RuntimeException attachFailure) {
                    return attachFailure;
                }
            }));
        }
        startLine.countDown();

        int successes = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<Throwable> outcome : outcomes) {
            Throwable failure = outcome.get();
            if (failure == null) {
                successes++;
            } else {
                failures.add(failure);
            }
        }
        pool.shutdown();

        // Exatamente um registro (imutável) — a corrida não duplica o anexo nem estoura 500.
        assertThat(attachments.findByCompanyIdAndIssueId(companyId, issueId)).hasSize(1);
        assertThat(successes).as("ao menos um anexo vence a corrida").isGreaterThanOrEqualTo(1);
        assertThat(failures).allSatisfy(failure ->
                assertThat(failure).isInstanceOf(ConflictException.class));
    }

    private static Agent with(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        return agent;
    }
}
