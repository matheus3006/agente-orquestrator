package com.orquestrador.memory;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O "Log de Decisões filtrável" (ADR-0019/D5): o quando vira feature — filtro por data/área/tipo.
 * BOARD-only: racional é material do dono (na tabela, nunca no activity_log — ADR-0009/0020).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/decisions")
public class DecisionController {

    private final DecisionLog decisionLog;
    private final CompanyAccessGuard guard;

    public DecisionController(DecisionLog decisionLog, CompanyAccessGuard guard) {
        this.decisionLog = decisionLog;
        this.guard = guard;
    }

    @GetMapping
    public List<DecisionResponse> query(@PathVariable UUID companyId,
                                        @RequestParam(required = false) String area,
                                        @RequestParam(required = false) DecisionTipo tipo,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        guard.assertBoardMember(companyId);
        return decisionLog.query(companyId, area, tipo, from, to).stream()
                .map(DecisionResponse::from).toList();
    }

    // seq = o #N humano da Decisão (C4, clone da ADR-0033); grau = o eixo do ALCANCE (C3).
    public record DecisionResponse(UUID id, int seq, Instant decidedAt, String area, String tipo,
                                   String grau, String origem, String oQueMudou, String racional,
                                   String racionalTipo, boolean racionalArrancado, UUID supersedesId,
                                   String evidencia, UUID issueId, UUID approvalId, UUID reviewId) {
        static DecisionResponse from(Decision decision) {
            return new DecisionResponse(decision.getId(), decision.getSeq(), decision.getDecidedAt(),
                    decision.getArea(), decision.getTipo().name(), decision.getGrau().name(),
                    decision.getOrigem().name(), decision.getOQueMudou(),
                    decision.getRacional(), decision.getRacionalTipo().name(),
                    decision.isRacionalArrancado(), decision.getSupersedesId(), decision.getEvidencia(),
                    decision.getIssueId(), decision.getApprovalId(), decision.getReviewId());
        }
    }
}
