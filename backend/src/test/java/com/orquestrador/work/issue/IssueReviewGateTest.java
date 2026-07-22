package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R.2 (ADR-0012): o review gate mora no branch de SUCESSO do {@code settleAfterRun}. Sucesso de alto
 * risco (>= limiar, default 5) → PENDING_REVIEW (lock solto) + review PENDING + evento, na MESMA tx;
 * abaixo do limiar → DONE inalterado; falha → BLOCKED intacto (§7 — o gate só ramifica dentro do sucesso).
 * O guard {@code execution_lock_run_id = runId} vale também no branch de review: run intruso é no-op.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueReviewGateTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private HeartbeatRunRepository runs;
    /** O UPDATE guardado cru: monta o lock que o serviço, desde a I-ter, recusa criar. */
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
    }

    /** Run REAL do motor (linha em heartbeat_run) — a review referencia um run concluído (FK da V11). */
    private UUID newRun(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, RunStatus.SUCCEEDED)).getId();
    }

    private List<ActivityLog> entriesOf(UUID companyId, ActivityAction action) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entry -> entry.getAction().equals(action.name())).toList();
    }

    @Test
    void sucessoComRiscoNoLimiarEstacionaEmPendingReview() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "mexe em producao", null, 5).getId();
        UUID runId = newRun(companyId, agentId, issueId);
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        assertThat(issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success()))
                .isEqualTo(IssueSettlement.SUBMITTED_FOR_REVIEW);

        Issue parked = issues.get(companyId, issueId);
        assertThat(parked.getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);
        assertThat(parked.getExecutionLockRunId()).isNull(); // lock solto: sem run em voo, não gasta
        assertThat(parked.getAssigneeAgentId()).isEqualTo(agentId);

        List<Review> cycle = reviews.findByCompanyIdAndIssueId(companyId, issueId);
        assertThat(cycle).hasSize(1);
        assertThat(cycle.get(0).getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(cycle.get(0).getRunId()).isEqualTo(runId);

        List<ActivityLog> submitted = entriesOf(companyId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW);
        assertThat(submitted).hasSize(1);
        ActivityLog event = submitted.get(0);
        assertThat(event.getActorType()).isEqualTo(ActorType.AGENT.name()); // ator = o agente que rodou
        assertThat(event.getActorId()).isEqualTo(agentId);
        assertThat(event.getEntityId()).isEqualTo(issueId);
        // detail só com IDs (ADR-0009): referencia o ciclo de review e o run revisado.
        assertThat(event.getDetail()).contains(cycle.get(0).getId().toString()).contains(runId.toString());
    }

    @Test
    void sucessoAbaixoDoLimiarViraDoneComoHoje() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "rotina", null, 4).getId();
        UUID runId = newRun(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);

        assertThat(issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success()))
                .isEqualTo(IssueSettlement.COMPLETED);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId)).isEmpty();
        assertThat(entriesOf(companyId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW)).isEmpty();
    }

    @Test
    void runIntrusoNaoEstacionaNemCriaReview() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        UUID owningRun = newRun(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, owningRun);

        // Run intruso "sucede" sem deter a Issue → guard casa 0 linhas, mesmo no branch de review.
        assertThat(issues.settleAfterRun(companyId, issueId, UUID.randomUUID(), RunOutcome.success()))
                .isEqualTo(IssueSettlement.NOT_SETTLED);

        Issue victim = issues.get(companyId, issueId);
        assertThat(victim.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(victim.getExecutionLockRunId()).isEqualTo(owningRun);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId)).isEmpty();
        assertThat(entriesOf(companyId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW)).isEmpty();
    }

    @Test
    void pendingReviewForaDoConjuntoPegavelNaoReRoda() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        UUID runId = newRun(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success()); // → PENDING_REVIEW

        // Nem o próprio agente re-adquire: PENDING_REVIEW não é pegável → não roda → não gasta budget.
        assertThat(issues.checkout(companyId, issueId, agentId, newRun(companyId, agentId, issueId))
                .acquired()).isFalse();
    }

    @Test
    void completeDoAgenteTambemPassaPeloGateSemBypass() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        UUID runId = newRun(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);

        // O caminho complete (o do endpoint agent-facing) NÃO pode furar o gate (achado ALTA da
        // revisão adversarial): concluir uma Issue de alto risco ESTACIONA, não faz DONE.
        assertThat(issues.complete(companyId, issueId, runId, agentId)).isTrue();

        Issue parked = issues.get(companyId, issueId);
        assertThat(parked.getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId)).hasSize(1);
        assertThat(entriesOf(companyId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW)).hasSize(1);
    }

    @Test
    void runSinteticoOuDeOutraCompanyNaoAbreCicloDeReview() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        // runId SINTÉTICO (sem linha em heartbeat_run): a review referencia um run concluído
        // (FK da V11) → o gate recusa com 409 e faz rollback TOTAL.
        // O carimbo vem do UPDATE guardado desde a Fatia I-ter — o /checkout não cria mais este
        // lock. O que este teste prova segue valendo e é OUTRA cerca: o settleAfterRun é o
        // chokepoint do MOTOR (não é agent-facing, não recebe ator) e não tem cerca de procedência,
        // então o estado vindo de outros produtores ainda chega aqui e ainda é recusado no gate.
        UUID syntheticRun = UUID.randomUUID();
        carimbarCru(issueId, companyId, agentId, syntheticRun);

        assertThatThrownBy(() -> issues.settleAfterRun(companyId, issueId, syntheticRun, RunOutcome.success()))
                .isInstanceOf(ConflictException.class);

        Issue intact = issues.get(companyId, issueId);
        assertThat(intact.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS); // rollback: não estacionou
        assertThat(intact.getExecutionLockRunId()).isEqualTo(syntheticRun);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId)).isEmpty();

        // Run REAL mas de OUTRA company (agente tentando referenciar run alheio): mesma recusa.
        UUID otherCompany = newCompany();
        UUID foreignRun = newRun(otherCompany, newAgent(otherCompany), null);
        UUID secondIssue = issues.create(companyId, "alto risco 2", null, 5).getId();
        carimbarCru(secondIssue, companyId, agentId, foreignRun);
        assertThatThrownBy(() -> issues.settleAfterRun(companyId, secondIssue, foreignRun, RunOutcome.success()))
                .isInstanceOf(ConflictException.class);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, secondIssue)).isEmpty();
    }

    @Test
    void falhaDeAltoRiscoContinuaBlockedGateSoRamificaNoSucesso() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        UUID runId = newRun(companyId, agentId, issueId);
        issues.checkout(companyId, issueId, agentId, runId);

        assertThat(issues.settleAfterRun(companyId, issueId, runId, RunOutcome.failure("boom")))
                .isEqualTo(IssueSettlement.BLOCKED);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(reviews.findByCompanyIdAndIssueId(companyId, issueId)).isEmpty(); // §7 intacto
    }

    /**
     * O UPDATE guardado precisa de transação (o {@code @Modifying} faz flush/clear). Mesmo recurso
     * que o {@code RowRetentionReaperTest} e o {@code ParecerGateTest} já usam para escrever cru.
     */
    private void carimbarCru(UUID issueId, UUID companyId, UUID agentId, UUID runId) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> issueRepo.tryCheckout(issueId, companyId, agentId, runId));
    }
}
