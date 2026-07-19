package com.orquestrador.governance.approval;

import com.orquestrador.common.error.BadRequestException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.governance.approval.ApprovalController.BatchDecisionResponse.SkippedItem;
import com.orquestrador.governance.approval.ApprovalService.DecideOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
    private final ApprovalBatchService batch;
    private final CompanyAccessGuard guard;
    private final RecusaKickback recusaKickback;

    public ApprovalController(ApprovalService approvals, ApprovalBatchService batch,
                              CompanyAccessGuard guard, RecusaKickback recusaKickback) {
        this.approvals = approvals;
        this.batch = batch;
        this.guard = guard;
        this.recusaKickback = recusaKickback;
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
        // A4 (ADR-0031): o trio de motor do request é a mão soberana do dono no menu do hire —
        // ausente/em branco cai no motor protocolado no alvo.
        ApprovalService.EngineOverride engine = new ApprovalService.EngineOverride(
                request.adapterType(), request.model(), request.effort());
        DecideOutcome outcome = approvals.decide(companyId, approvalId, request.decision(),
                request.racional(), request.reason(), actor.type(), actor.id(), engine,
                request.supersedesSeq(), request.fraseDoPrincipio());
        // D15 (ADR-0035): recusa commitada VOLTA ao proponente na conversa de origem. O gancho
        // fica no CALLER (não no decide) de propósito: o lote agrega por conversa antes de
        // postar (D7) — decide() por item postando quebraria a agregação.
        if (outcome instanceof DecideOutcome.Committed committed
                && request.decision() == ApprovalDecision.REJECT) {
            recusaKickback.notificarRecusaIndividual(committed.approval());
        }
        return switch (outcome) {
            case DecideOutcome.Grilled grilled -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new GrilledResponse(true, grilled.probe(), grilled.rounds()));
            case DecideOutcome.Committed committed ->
                    ResponseEntity.ok(ApprovalResponse.from(committed.approval()));
        };
    }

    /**
     * O lote (ADR-0035, D10): decisão + racional + motivo compartilhados, observação opcional
     * por item (D8). SEM trio de motor de propósito (D12) — motor errado num HIRE_AGENT tem
     * consequência cara; o escape é decidir aquele item na tela individual, onde A4 já vale.
     */
    @PostMapping("/batch-decision")
    public BatchDecisionResponse decideBatch(@PathVariable UUID companyId,
                                             @RequestBody @Valid BatchDecisionRequest request) {
        guard.assertBoardMember(companyId);
        if (request.decision() == ApprovalDecision.REJECT
                && (request.motivo() == null || request.motivo().isBlank())) {
            throw new BadRequestException(
                    "Recusa em lote exige o motivo comum — e o kickback ao CEO que o carrega (D8)");
        }
        Actor actor = guard.currentActor();
        ApprovalBatchService.BatchOutcome outcome = batch.decideBatch(companyId, request.decision(),
                request.racional(), request.motivo(),
                request.items().stream()
                        .map(item -> new ApprovalBatchService.Item(item.approvalId(), item.observacao()))
                        .toList(),
                actor.type(), actor.id());
        return new BatchDecisionResponse(outcome.decididas().size(),
                outcome.puladas().stream()
                        .map(pulada -> new SkippedItem(pulada.approvalId(), pulada.motivo()))
                        .toList(),
                outcome.decididas().stream().map(ApprovalResponse::from).toList());
    }

    /**
     * O trio adapterType/model/effort é o motor editado pelo dono no menu do hire (A4).
     * supersedesSeq (opcional, D11): o #N citado pelo CEO que esta decisão supersede — o dono
     * sela; a carta se auto-cura (o leitor já filtra supersedidas). #N fantasma → 409.
     * fraseDoPrincipio (opcional, F-C2/C2): a frase EDITADA no card do GRADUATE_PRINCIPLE —
     * o dono é o writer-of-record do Playbook; em branco vale a frase protocolada no alvo.
     */
    public record DecisionRequest(@NotNull ApprovalDecision decision,
                                  @Size(max = 4000) String racional,
                                  @Size(max = 2000) String reason,
                                  @Size(max = 120) String adapterType,
                                  @Size(max = 120) String model,
                                  @Size(max = 120) String effort,
                                  Integer supersedesSeq,
                                  @Size(max = 2000) String fraseDoPrincipio) {
    }

    /** O 422 do grill: o commit NÃO aconteceu; o probe é a pergunta do escriba-que-insiste. */
    public record GrilledResponse(boolean grilled, String probe, int rounds) {
    }

    public record BatchDecisionRequest(@NotNull ApprovalDecision decision,
                                       @Size(max = 4000) String racional,
                                       @Size(max = 2000) String motivo,
                                       @NotEmpty List<@Valid BatchItem> items) {
        public record BatchItem(@NotNull UUID approvalId, @Size(max = 2000) String observacao) {
        }
    }

    /** A contagem do lote sai do loop de graça (D9): decididas + puladas com o porquê de cada uma. */
    public record BatchDecisionResponse(int decided, List<SkippedItem> skipped,
                                        List<ApprovalResponse> results) {
        public record SkippedItem(UUID approvalId, String motivo) {
        }
    }

    // decidedByActorType/Id (ADR-0047, item 12 da conferência): "QUEM decidiu" sempre existiu na
    // entidade/banco mas nunca era serializado — vale pra QUALQUER approval; a fila precisa dele
    // pra renderizar "liberado por <gestor>" no cartão nasce-decidido (decidedByActorType=AGENT).
    public record ApprovalResponse(UUID id, UUID requestedByAgentId, UUID conversationId, UUID runId,
                                   String actionType, String targetDetail, String area, String resumo,
                                   String status, String racional, String racionalTipo,
                                   boolean racionalArrancado, int grillRounds, String decisionReason,
                                   String decidedByActorType, UUID decidedByActorId,
                                   Instant requestedAt, Instant decidedAt) {
        static ApprovalResponse from(Approval approval) {
            return new ApprovalResponse(approval.getId(), approval.getRequestedByAgentId(),
                    approval.getConversationId(), approval.getRunId(), approval.getActionType().name(),
                    approval.getTargetDetail(), approval.getArea(), approval.getResumo(),
                    approval.getStatus().name(), approval.getRacional(),
                    approval.getRacionalTipo().name(), approval.isRacionalArrancado(),
                    approval.getGrillRounds(), approval.getDecisionReason(),
                    approval.getDecidedByActorType() != null ? approval.getDecidedByActorType().name() : null,
                    approval.getDecidedByActorId(),
                    approval.getRequestedAt(), approval.getDecidedAt());
        }
    }
}
