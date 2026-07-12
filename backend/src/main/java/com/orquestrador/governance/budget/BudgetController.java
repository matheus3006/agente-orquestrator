package com.orquestrador.governance.budget;

import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/companies/{companyId}")
public class BudgetController {

    private final BudgetService budgets;
    private final CompanyAccessGuard guard;

    public BudgetController(BudgetService budgets, CompanyAccessGuard guard) {
        this.budgets = budgets;
        this.guard = guard;
    }

    @PostMapping("/budget-policies")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyResponse createPolicy(@PathVariable UUID companyId, @RequestBody @Valid CreatePolicyRequest request) {
        guard.assertMember(companyId);
        ScopeType scope = ScopeType.valueOf(request.scopeType().trim().toUpperCase());
        UUID scopeId = scope == ScopeType.COMPANY ? companyId : request.scopeId();
        BudgetPolicy policy = budgets.createPolicy(companyId, scope, scopeId, request.limitCents(), request.warnCents());
        return new PolicyResponse(policy.getId(), policy.getScopeType().name(), policy.getScopeId(), policy.getLimitCents());
    }

    /** Reporta um custo (agente ou board). Dispara o enforcement reativo. */
    @PostMapping("/cost-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void recordCost(@PathVariable UUID companyId, @RequestBody CostRequest request) {
        guard.assertMember(companyId);
        budgets.recordCost(companyId, request.agentId(), request.runId(), request.issueId(),
                request.provider(), request.model(), request.costCents(),
                zeroIfNull(request.tokensInput()), zeroIfNull(request.tokensOutput()));
    }

    @GetMapping("/budget-incidents")
    public List<IncidentResponse> incidents(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        return budgets.incidentsFor(companyId).stream().map(IncidentResponse::from).toList();
    }

    /**
     * A superfície do gasto off-book (F-N5/ADR-0041, D5): os lançamentos de token de um run,
     * marcados {@code offBook} quando o gasto é de subagente (outra janela de contexto).
     */
    @GetMapping("/runs/{runId}/cost-events")
    public List<CostEventResponse> costEvents(@PathVariable UUID companyId, @PathVariable UUID runId) {
        guard.assertMember(companyId);
        return budgets.costEventsForRun(companyId, runId).stream().map(CostEventResponse::from).toList();
    }

    /** Override de governanca: resolve o incidente e destrava o escopo. */
    @PostMapping("/budget-incidents/{incidentId}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID companyId, @PathVariable UUID incidentId) {
        guard.assertMember(companyId);
        budgets.resolveIncident(companyId, incidentId);
    }

    private static int zeroIfNull(Integer value) {
        return value != null ? value : 0;
    }

    // limitCents DEVE ser positivo: um limite ≤ 0 é degenerado (spent ≥ 0 sempre estoura) e abriria
    // incidente espúrio já na primeira emissão de tokens costCents=0 (ADR-0006/0014 Q7).
    public record CreatePolicyRequest(String scopeType, UUID scopeId, @Positive long limitCents, Long warnCents) {
    }

    public record PolicyResponse(UUID id, String scopeType, UUID scopeId, long limitCents) {
    }

    public record CostRequest(UUID agentId, UUID runId, UUID issueId, String provider, String model,
                              long costCents, Integer tokensInput, Integer tokensOutput) {
    }

    public record IncidentResponse(UUID id, String scopeType, UUID scopeId, long spentCents,
                                   long limitCents, String status, Instant createdAt) {
        static IncidentResponse from(BudgetIncident incident) {
            return new IncidentResponse(incident.getId(), incident.getScopeType().name(), incident.getScopeId(),
                    incident.getSpentCents(), incident.getLimitCents(), incident.getStatus().name(),
                    incident.getCreatedAt());
        }
    }

    public record CostEventResponse(UUID id, String provider, String model, int tokensInput,
                                    int tokensOutput, boolean offBook, Instant createdAt) {
        static CostEventResponse from(CostEvent event) {
            return new CostEventResponse(event.getId(), event.getProvider(), event.getModel(),
                    event.getTokensInput(), event.getTokensOutput(), event.isOffBook(), event.getCreatedAt());
        }
    }
}
