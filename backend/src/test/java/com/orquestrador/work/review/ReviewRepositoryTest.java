package com.orquestrador.work.review;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R.1 (ADR-0012): a {@code review} é a entidade request→decision do gate — nasce PENDING apontando o run
 * concluído; os campos de decisão (reviewer, decided_at, decision_reason) ficam null até o board decidir (R.3).
 * Queries lideradas por company_id (deny-by-default): cross-company não vaza.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewRepositoryTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private ReviewRepository reviews;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
    }

    private UUID newIssue(UUID companyId) {
        return issues.create(companyId, "tarefa", null).getId();
    }

    private UUID newFinishedRun(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, RunStatus.SUCCEEDED)).getId();
    }

    @Test
    void reviewNascePendingComRunEDecisaoVazia() {
        UUID companyId = newCompany();
        UUID issueId = newIssue(companyId);
        UUID runId = newFinishedRun(companyId, newAgent(companyId), issueId);

        reviews.save(new Review(UUID.randomUUID(), companyId, issueId, runId));

        List<Review> found = reviews.findByCompanyIdAndIssueId(companyId, issueId);
        assertThat(found).hasSize(1);
        Review review = found.get(0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(review.getRunId()).isEqualTo(runId);
        assertThat(review.getRequestedAt()).isNotNull();
        assertThat(review.getReviewerActorType()).isNull();
        assertThat(review.getReviewerActorId()).isNull();
        assertThat(review.getDecisionReason()).isNull();
        assertThat(review.getDecidedAt()).isNull();
    }

    @Test
    void crossCompanyNaoVaza() {
        UUID companyA = newCompany();
        UUID companyB = newCompany();
        UUID issueA = newIssue(companyA);
        UUID runA = newFinishedRun(companyA, newAgent(companyA), issueA);
        reviews.save(new Review(UUID.randomUUID(), companyA, issueA, runA));

        assertThat(reviews.findByCompanyIdAndIssueId(companyB, issueA)).isEmpty();
        assertThat(reviews.findByCompanyIdAndStatusOrderByRequestedAt(companyB, ReviewStatus.PENDING)).isEmpty();
    }

    @Test
    void filaPendenteOrdenaPorRequestedAt() throws Exception {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID primeiraIssue = newIssue(companyId);
        UUID segundaIssue = newIssue(companyId);

        Review primeira = new Review(UUID.randomUUID(), companyId, primeiraIssue,
                newFinishedRun(companyId, agentId, primeiraIssue));
        reviews.save(primeira);
        Thread.sleep(5); // separa os requested_at (ordem da fila é por chegada)
        Review segunda = new Review(UUID.randomUUID(), companyId, segundaIssue,
                newFinishedRun(companyId, agentId, segundaIssue));
        reviews.save(segunda);

        List<Review> fila = reviews.findByCompanyIdAndStatusOrderByRequestedAt(companyId, ReviewStatus.PENDING);
        assertThat(fila).extracting(Review::getId).containsExactly(primeira.getId(), segunda.getId());
    }
}
