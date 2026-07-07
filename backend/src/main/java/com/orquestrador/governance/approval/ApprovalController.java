package com.orquestrador.governance.approval;

import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.governance.approval.ApprovalService.DecideOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A fila de Approvals do board (T.3, ADR-0018 — a MESMA fila que a thread projeta como cards
 * ancorados) + o commit da decisão com grill (ADR-0020): racional ausente/confabulado responde
 * 422 com o PROBE (o commit não aconteceu; a rodada fica contada) — o front mostra a pergunta e
 * re-envia. BOARD-only nas duas pontas (deny-by-default).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/approvals")
public class ApprovalController {

    private final ApprovalService approvals;
    private final CompanyAccessGuard guard;

    public ApprovalController(ApprovalService approvals, CompanyAccessGuard guard) {
        this.approvals = approvals;
        this.guard = guard;
    }

    @GetMapping
    public List<ApprovalResponse> queue(@PathVariable UUID companyId,
                                        @RequestParam(defaultValue = "PENDING") ApprovalStatus status,
                                        @RequestParam(required = false) UUID conversationId) {
        guard.assertBoardMember(companyId);
        List<Approval> result = conversationId != null
                ? approvals.anchoredTo(companyId, conversationId)
                : approvals.queue(companyId, status);
        return result.stream().map(ApprovalResponse::from).toList();
    }

    @PostMapping("/{approvalId}/decision")
    public ResponseEntity<?> decide(@PathVariable UUID companyId, @PathVariable UUID approvalId,
                                    @RequestBody @Valid DecisionRequest request) {
        guard.assertBoardMember(companyId);
        Actor actor = guard.currentActor();
        DecideOutcome outcome = approvals.decide(companyId, approvalId, request.decision(),
                request.racional(), request.reason(), actor.type(), actor.id());
        return switch (outcome) {
            case DecideOutcome.Grilled grilled -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new GrilledResponse(true, grilled.probe(), grilled.rounds()));
            case DecideOutcome.Committed committed ->
                    ResponseEntity.ok(ApprovalResponse.from(committed.approval()));
        };
    }

    public record DecisionRequest(@NotNull ApprovalDecision decision,
                                  @Size(max = 4000) String racional,
                                  @Size(max = 2000) String reason) {
    }

    /** O 422 do grill: o commit NÃO aconteceu; o probe é a pergunta do escriba-que-insiste. */
    public record GrilledResponse(boolean grilled, String probe, int rounds) {
    }

    public record ApprovalResponse(UUID id, UUID requestedByAgentId, UUID conversationId, UUID runId,
                                   String actionType, String targetDetail, String area, String resumo,
                                   String status, String racional, String racionalTipo,
                                   boolean racionalArrancado, int grillRounds, String decisionReason,
                                   Instant requestedAt, Instant decidedAt) {
        static ApprovalResponse from(Approval approval) {
            return new ApprovalResponse(approval.getId(), approval.getRequestedByAgentId(),
                    approval.getConversationId(), approval.getRunId(), approval.getActionType().name(),
                    approval.getTargetDetail(), approval.getArea(), approval.getResumo(),
                    approval.getStatus().name(), approval.getRacional(),
                    approval.getRacionalTipo().name(), approval.isRacionalArrancado(),
                    approval.getGrillRounds(), approval.getDecisionReason(),
                    approval.getRequestedAt(), approval.getDecidedAt());
        }
    }
}
