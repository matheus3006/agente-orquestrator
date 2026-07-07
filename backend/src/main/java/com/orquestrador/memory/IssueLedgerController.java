package com.orquestrador.memory;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * O report do Ledger por-Issue (ADR-0019/D3), board-only: o corpo legível do card (mockup §4 —
 * report .md, nunca JSON cru/StepTrace bruto). 404 enquanto nenhum run concluiu.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/issues/{issueId}/report")
public class IssueLedgerController {

    private final IssueLedger ledger;
    private final CompanyAccessGuard guard;

    public IssueLedgerController(IssueLedger ledger, CompanyAccessGuard guard) {
        this.ledger = ledger;
        this.guard = guard;
    }

    @GetMapping
    public IssueLedger.IssueReport report(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        guard.assertBoardMember(companyId);
        return ledger.reportFor(companyId, issueId)
                .orElseThrow(() -> new NotFoundException(
                        "Issue sem run concluido — o Ledger nao tem report ainda: " + issueId));
    }
}
