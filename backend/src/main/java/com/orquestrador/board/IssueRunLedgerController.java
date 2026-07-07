package com.orquestrador.board;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.work.issue.IssueRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F.0: o ledger de execuções do Issue detail — toda tentativa (com desfecho, tempos e erro),
 * mais recente primeiro. Board-only; Issue company-led → fora da company é 404.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/issues/{issueId}/runs")
public class IssueRunLedgerController {

    private final HeartbeatRunRepository runs;
    private final IssueRepository issues;
    private final CompanyAccessGuard guard;

    public IssueRunLedgerController(HeartbeatRunRepository runs, IssueRepository issues,
                                    CompanyAccessGuard guard) {
        this.runs = runs;
        this.issues = issues;
        this.guard = guard;
    }

    @GetMapping
    public List<RunLedgerEntry> ledger(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        guard.assertBoardMember(companyId);
        issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada nesta company"));
        return runs.findByIssueIdAndCompanyIdOrderByCreatedAtDesc(issueId, companyId).stream()
                .map(RunLedgerEntry::from)
                .toList();
    }

    public record RunLedgerEntry(UUID id, UUID agentId, UUID issueId, String status, int attempt,
                                 Instant createdAt, Instant startedAt, Instant finishedAt,
                                 String lastError) {
        static RunLedgerEntry from(HeartbeatRun run) {
            return new RunLedgerEntry(run.getId(), run.getAgentId(), run.getIssueId(),
                    run.getStatus().name(), run.getAttempt(), run.getCreatedAt(),
                    run.getStartedAt(), run.getFinishedAt(), run.getLastError());
        }
    }
}
