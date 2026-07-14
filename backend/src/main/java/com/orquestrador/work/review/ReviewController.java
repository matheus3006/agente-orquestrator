package com.orquestrador.work.review;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.governance.approval.RacionalPolicy;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A fila de revisão do board (R.4, ADR-0012): lista os ciclos de review da company por status
 * (default PENDING = o que espera decisão), em ordem de chegada. BOARD-only — o conteúdo inclui
 * o decision_reason (dado de negócio da tabela review; fora do activity_log por PII, ADR-0009).
 *
 * <p>F-C1 (C14): cada card sai com a ÁREA do artefato (C11), o tipo derivado (D9) e o
 * {@code racionalProbe} — a pergunta não-bloqueante do approve, nas palavras da POLÍTICA
 * ({@link RacionalPolicy#reviewProbe}); a UI é dona só do MOMENTO de mostrá-la.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/reviews")
public class ReviewController {

    private final ReviewRepository reviews;
    private final IssueRepository issues;
    private final IssueService issueService;
    private final RacionalPolicy racionalPolicy;
    private final CompanyAccessGuard guard;

    public ReviewController(ReviewRepository reviews, IssueRepository issues,
                            IssueService issueService, RacionalPolicy racionalPolicy,
                            CompanyAccessGuard guard) {
        this.reviews = reviews;
        this.issues = issues;
        this.issueService = issueService;
        this.racionalPolicy = racionalPolicy;
        this.guard = guard;
    }

    @GetMapping
    public List<ReviewResponse> queue(@PathVariable UUID companyId,
                                      @RequestParam(defaultValue = "PENDING") ReviewStatus status) {
        guard.assertMember(companyId);
        requireBoard();
        return reviews.findByCompanyIdAndStatusOrderByRequestedAt(companyId, status).stream()
                .map(this::respond).toList();
    }

    /** Degrada, nunca quebra: issue sumida (retenção) → card sem área/probe, a fila segue. */
    private ReviewResponse respond(Review review) {
        Issue issue = issues.findByIdAndCompanyId(review.getIssueId(), review.getCompanyId())
                .orElse(null);
        String area = issue != null ? issueService.areaDaIssue(issue) : null;
        RacionalTipo tipo = area != null ? racionalPolicy.deriveTipo(area) : null;
        return ReviewResponse.from(review, area, tipo,
                tipo != null ? racionalPolicy.reviewProbe(tipo) : null);
    }

    private void requireBoard() {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) ve a fila de revisao");
        }
    }

    public record ReviewResponse(UUID id, UUID issueId, UUID runId, String status,
                                 String reviewerActorType, UUID reviewerActorId,
                                 String decisionReason, Instant requestedAt, Instant decidedAt,
                                 String area, String racionalTipo, String racionalProbe) {
        static ReviewResponse from(Review review, String area, RacionalTipo tipo, String probe) {
            return new ReviewResponse(review.getId(), review.getIssueId(), review.getRunId(),
                    review.getStatus().name(),
                    review.getReviewerActorType() != null ? review.getReviewerActorType().name() : null,
                    review.getReviewerActorId(), review.getDecisionReason(),
                    review.getRequestedAt(), review.getDecidedAt(),
                    area, tipo != null ? tipo.name() : null, probe);
        }
    }
}
