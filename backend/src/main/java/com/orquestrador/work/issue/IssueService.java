package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewDecision;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IssueService {

    private final IssueRepository issues;
    private final ReviewRepository reviews;
    private final HeartbeatRunRepository heartbeatRuns;
    private final ActivityLogService activityLog;
    private final int reviewRiskThreshold;

    public IssueService(IssueRepository issues, ReviewRepository reviews, HeartbeatRunRepository heartbeatRuns,
                        ActivityLogService activityLog,
                        @Value("${orquestrador.review.risk-threshold:5}") int reviewRiskThreshold) {
        // Fora da escala 1-5 o gate desligaria SILENCIOSAMENTE (limiar 6 nunca é atingível) —
        // fail-open de mecanismo de segurança. Melhor derrubar o boot com mensagem clara.
        if (reviewRiskThreshold < 1 || reviewRiskThreshold > 5) {
            throw new IllegalArgumentException(
                    "orquestrador.review.risk-threshold deve estar na escala 1-5 (ADR-0012); veio: "
                            + reviewRiskThreshold);
        }
        this.issues = issues;
        this.reviews = reviews;
        this.heartbeatRuns = heartbeatRuns;
        this.activityLog = activityLog;
        this.reviewRiskThreshold = reviewRiskThreshold;
    }

    @Transactional
    public Issue create(UUID companyId, String title, String description) {
        return create(companyId, title, description, null);
    }

    @Transactional
    public Issue create(UUID companyId, String title, String description, Integer riskLevel) {
        int effectiveRiskLevel = riskLevel != null ? riskLevel : 1;
        return issues.save(new Issue(UUID.randomUUID(), companyId, title, description, effectiveRiskLevel));
    }

    /**
     * Checkout atomico. Gera um runId se nao vier (na fatia 1 nao ha heartbeat ainda;
     * a partir da fatia 2 o run vem do motor de heartbeat).
     */
    @Transactional
    public CheckoutResult checkout(UUID companyId, UUID issueId, UUID agentId, UUID runId) {
        UUID effectiveRun = runId != null ? runId : UUID.randomUUID();
        int rows = issues.tryCheckout(issueId, companyId, agentId, effectiveRun);
        if (rows == 0) {
            return CheckoutResult.conflict();
        }
        Issue issue = issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada: " + issueId));
        return CheckoutResult.acquired(issue, effectiveRun);
    }

    /**
     * Conclusão pelo caminho agent-facing (endpoint /complete): roteia pelo MESMO funil do
     * {@link #settleAfterRun} — alto risco estaciona em PENDING_REVIEW em vez de DONE. Um único
     * caminho para DONE fecha o bypass "o agente pulou a revisão" (ADR-0006/0012; achado ALTA
     * da revisão adversarial da R.2).
     */
    @Transactional
    public boolean complete(UUID companyId, UUID issueId, UUID runId) {
        return settleAfterRun(companyId, issueId, runId, RunOutcome.success()) != IssueSettlement.NOT_SETTLED;
    }

    /**
     * Liquida a Issue na saída terminal de um Run (recovery ADR-0011 + review gate ADR-0012).
     * Sucesso → {@code DONE}, OU {@code PENDING_REVIEW} se {@code risk_level >= limiar} (o gate intercepta
     * o DONE: estaciona + cria o ciclo de {@code review} PENDING + evento, na MESMA tx); falha →
     * {@code BLOCKED} (park-for-human) + evento {@code ISSUE_BLOCKED}. SEMPRE libera o lock, sob o
     * guard {@code execution_lock_run_id = runId}: se o run NÃO detinha a Issue (falhou antes do checkout,
     * ou perdeu a corrida), casa 0 linhas → {@link IssueSettlement#NOT_SETTLED} (a vítima fica intacta) —
     * inclusive no branch de review (nenhum ciclo/evento é criado por um run intruso).
     *
     * <p>Recebe o {@link RunOutcome} (não um estado-alvo) para o futuro: quando o erro tipado do ADR-0004
     * for cabeado, o ramo {@code transient_upstream → RECOVERING}(+retryNotBefore) entra AQUI, sem tocar o caller.
     */
    @Transactional
    public IssueSettlement settleAfterRun(UUID companyId, UUID issueId, UUID runId, RunOutcome outcome) {
        if (outcome.succeeded()) {
            Issue settling = issues.findByIdAndCompanyId(issueId, companyId).orElse(null);
            if (settling != null && settling.getRiskLevel() >= reviewRiskThreshold) {
                return submitForReview(companyId, issueId, runId, settling.getAssigneeAgentId());
            }
            return issues.tryComplete(issueId, companyId, runId) == 1
                    ? IssueSettlement.COMPLETED : IssueSettlement.NOT_SETTLED;
        }
        if (issues.tryBlock(issueId, companyId, runId) == 0) {
            return IssueSettlement.NOT_SETTLED;
        }
        // Ator = o agente que rodou (assignee fixado no checkout). detail só com IDs (ADR-0009): sem a
        // mensagem de erro (pode conter texto sensível — o erro vive no StepTrace do run).
        UUID actingAgentId = issues.findByIdAndCompanyId(issueId, companyId)
                .map(Issue::getAssigneeAgentId).orElse(null);
        activityLog.record(companyId, ActorType.AGENT, actingAgentId, ActivityAction.ISSUE_BLOCKED,
                "issue", issueId, Map.of("runId", runId));
        return IssueSettlement.BLOCKED;
    }

    /**
     * O review gate (ADR-0012): estaciona em PENDING_REVIEW (guard = só o run detentor; 0 linhas → no-op,
     * SEM criar ciclo nem evento) e abre o ciclo de review PENDING apontando o run revisado — tudo na
     * MESMA tx do settle. Ator do evento = o agente que rodou (assignee fixado no checkout); detail só
     * com IDs (ADR-0009) — o decision_reason futuro vive na tabela review, nunca no log.
     */
    private IssueSettlement submitForReview(UUID companyId, UUID issueId, UUID runId, UUID actingAgentId) {
        if (issues.trySubmitForReview(issueId, companyId, runId) == 0) {
            return IssueSettlement.NOT_SETTLED;
        }
        // A review referencia um run concluído REAL desta company ("toda review corresponde a um run",
        // ADR-0012; a FK da V11 é o backstop Postgres). O caminho manual da fatia 1 (runId sintético,
        // sem linha em heartbeat_run) e um runId de outra company são recusados: 409 + rollback TOTAL
        // (o UPDATE guardado acima desfaz — a Issue segue IN_PROGRESS com o lock).
        if (!heartbeatRuns.existsByIdAndCompanyId(runId, companyId)) {
            throw new ConflictException(
                    "review exige um run real do motor de heartbeat desta company; runId sintetico/alheio: " + runId);
        }
        Review cycle = reviews.save(new Review(UUID.randomUUID(), companyId, issueId, runId));
        activityLog.record(companyId, ActorType.AGENT, actingAgentId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW,
                "issue", issueId, Map.of("reviewId", cycle.getId(), "runId", runId));
        return IssueSettlement.SUBMITTED_FOR_REVIEW;
    }

    /**
     * A decisão do board sobre uma Issue estacionada (review gate, ADR-0012): APPROVE →
     * {@code DONE}; REJECT → {@code BLOCKED} (o recovery é 100% o {@link #unblock} do §7 — o
     * feedback do revisor entra editando a Issue antes do REOPEN). Transição guardada por
     * {@code status = 'PENDING_REVIEW'} (0 linhas → 409) + o ciclo {@code review} vira
     * APPROVED/REJECTED com reviewer/decided_at na MESMA tx. O {@code decision_reason} (texto
     * livre) fica NA TABELA review; o evento carrega só o {@code reviewId} (regra PII, ADR-0009).
     *
     * <p>{@code expectedReviewId} (opcional) ANCORA a decisão no ciclo que o revisor viu: se o ciclo
     * aberto for outro (um APPROVE atrasado/duplicado chegando depois de reject→REOPEN→re-run abrir
     * um NOVO ciclo), 409 + rollback — uma decisão do ciclo N nunca aprova o output não-revisado do
     * ciclo N+1. Sem âncora, decide o ciclo corrente (conveniência do board solo v1).
     */
    @Transactional
    public void review(UUID companyId, UUID issueId, ReviewDecision decision, String reason,
                       UUID expectedReviewId, ActorType actorType, UUID actorId) {
        int rows = switch (decision) {
            case APPROVE -> issues.tryApprove(issueId, companyId);
            case REJECT -> issues.tryReject(issueId, companyId);
        };
        if (rows == 0) {
            throw new ConflictException("Issue nao esta PENDING_REVIEW (decisao so vale em revisao): " + issueId);
        }
        Review cycle = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issueId, ReviewStatus.PENDING)
                .orElseThrow(() -> new ConflictException(
                        "Issue em PENDING_REVIEW sem ciclo de review aberto (inconsistencia): " + issueId));
        if (expectedReviewId != null && !cycle.getId().equals(expectedReviewId)) {
            throw new ConflictException("decisao ancorada no ciclo " + expectedReviewId
                    + " mas o ciclo aberto e " + cycle.getId() + " (re-revise o output atual)");
        }
        if (decision == ReviewDecision.APPROVE) {
            cycle.approve(actorType, actorId, reason);
        } else {
            cycle.reject(actorType, actorId, reason);
        }
        reviews.save(cycle);
        ActivityAction action = decision == ReviewDecision.APPROVE
                ? ActivityAction.REVIEW_APPROVED : ActivityAction.REVIEW_REJECTED;
        activityLog.record(companyId, actorType, actorId, action, "issue", issueId,
                Map.of("reviewId", cycle.getId()));
    }

    /**
     * Destrava uma Issue {@code BLOCKED} por decisão do board (ADR-0011): {@code REOPEN} → {@code OPEN}
     * (mantém o assignee → o próximo wakeup re-executa com o mesmo agente); {@code CANCEL} → {@code CANCELLED}.
     * Guardado por {@code status = 'BLOCKED'}: destravar uma Issue não-bloqueada → 409.
     */
    @Transactional
    public void unblock(UUID companyId, UUID issueId, UnblockDecision decision, ActorType actorType, UUID actorId) {
        int rows = switch (decision) {
            case REOPEN -> issues.tryReopen(issueId, companyId);
            case CANCEL -> issues.tryCancel(issueId, companyId);
        };
        if (rows == 0) {
            throw new ConflictException("Issue nao esta BLOCKED (destrava so vale para Issue bloqueada): " + issueId);
        }
        ActivityAction action = decision == UnblockDecision.REOPEN
                ? ActivityAction.ISSUE_REOPENED : ActivityAction.ISSUE_CANCELLED;
        activityLog.record(companyId, actorType, actorId, action, "issue", issueId, Map.of());
    }

    @Transactional(readOnly = true)
    public List<Issue> list(UUID companyId) {
        return issues.findByCompanyId(companyId);
    }

    @Transactional(readOnly = true)
    public Issue get(UUID companyId, UUID issueId) {
        return issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada: " + issueId));
    }
}
