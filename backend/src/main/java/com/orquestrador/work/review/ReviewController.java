package com.orquestrador.work.review;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.CompanyAccessGuard;
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
 */
@RestController
@RequestMapping("/api/companies/{companyId}/reviews")
public class ReviewController {

    private final ReviewRepository reviews;
    private final CompanyAccessGuard guard;

    public ReviewController(ReviewRepository reviews, CompanyAccessGuard guard) {
        this.reviews = reviews;
        this.guard = guard;
    }

    @GetMapping
    public List<ReviewResponse> queue(@PathVariable UUID companyId,
                                      @RequestParam(defaultValue = "PENDING") ReviewStatus status) {
        guard.assertMember(companyId);
        requireBoard();
        return reviews.findByCompanyIdAndStatusOrderByRequestedAt(companyId, status).stream()
                .map(ReviewResponse::from).toList();
    }

    private void requireBoard() {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) ve a fila de revisao");
        }
    }

    public record ReviewResponse(UUID id, UUID issueId, UUID runId, String status,
                                 String reviewerActorType, UUID reviewerActorId,
                                 String decisionReason, Instant requestedAt, Instant decidedAt) {
        static ReviewResponse from(Review review) {
            return new ReviewResponse(review.getId(), review.getIssueId(), review.getRunId(),
                    review.getStatus().name(),
                    review.getReviewerActorType() != null ? review.getReviewerActorType().name() : null,
                    review.getReviewerActorId(), review.getDecisionReason(),
                    review.getRequestedAt(), review.getDecidedAt());
        }
    }
}
