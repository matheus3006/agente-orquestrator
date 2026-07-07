package com.orquestrador.execution;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies/{companyId}/agents/{agentId}")
public class WakeupController {

    private final WakeupService wakeups;
    private final HeartbeatRunRepository runs;
    private final CompanyAccessGuard guard;

    public WakeupController(WakeupService wakeups, HeartbeatRunRepository runs, CompanyAccessGuard guard) {
        this.wakeups = wakeups;
        this.runs = runs;
        this.guard = guard;
    }

    @PostMapping("/wakeups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WakeupResponse wake(@PathVariable UUID companyId, @PathVariable UUID agentId,
                               @RequestBody(required = false) WakeupRequest request) {
        guard.assertMember(companyId);
        String reason = (request != null && request.reason() != null && !request.reason().isBlank()) ? request.reason() : "manual";
        UUID issueId = request != null ? request.issueId() : null;
        WakeupOutcome outcome = wakeups.enqueue(companyId, agentId, issueId, reason);
        return new WakeupResponse(outcome.runId(), outcome.created());
    }

    @GetMapping("/heartbeat-runs")
    public List<RunResponse> runs(@PathVariable UUID companyId, @PathVariable UUID agentId) {
        guard.assertMember(companyId);
        return runs.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(run -> run.getAgentId().equals(agentId))
                .map(RunResponse::from)
                .toList();
    }

    public record WakeupRequest(UUID issueId, String reason) {
    }

    public record WakeupResponse(UUID runId, boolean created) {
    }

    public record RunResponse(UUID id, UUID agentId, UUID issueId, String status, int attempt, Instant createdAt) {
        static RunResponse from(HeartbeatRun run) {
            return new RunResponse(run.getId(), run.getAgentId(), run.getIssueId(),
                    run.getStatus().name(), run.getAttempt(), run.getCreatedAt());
        }
    }
}
