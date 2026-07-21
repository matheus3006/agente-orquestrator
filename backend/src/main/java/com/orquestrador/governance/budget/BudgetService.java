package com.orquestrador.governance.budget;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.execution.AgentWakeupRequestRepository;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Budget hard-stop em duas fases (ADR-0006):
 * 1) PREFLIGHT — {@link #invocationBlocked} consultado pelo dispatcher antes de iniciar um Run.
 * 2) REATIVO — {@link #recordCost}/{@link #recordOrchestration} recomputam o gasto do ledger a
 *    cada lancamento; cruzou o hard limit → abre incidente e DRENA o escopo ate o checkpoint
 *    (T.4, §A.6-#3): cancela o que esta em QUEUED (fila + wakeups) e deixa o EM-VOO terminar
 *    o passo e liquidar (settle/report) — matar RUNNING no banco nunca matou o processo, so
 *    jogava o resultado fora. Nada novo nasce: o preflight barra o follow-up.
 */
@Service
public class BudgetService {

    private static final List<RunStatus> CANCELLABLE_RUNS = List.of(RunStatus.QUEUED);
    private static final List<WakeupStatus> CANCELLABLE_WAKEUPS =
            List.of(WakeupStatus.PENDING, WakeupStatus.COALESCED, WakeupStatus.DEFERRED);

    private final CostEventRepository costs;
    private final BudgetPolicyRepository policies;
    private final BudgetIncidentRepository incidents;
    private final HeartbeatRunRepository runs;
    private final AgentWakeupRequestRepository wakeups;
    private final HeartbeatRunEventRepository events;

    public BudgetService(CostEventRepository costs, BudgetPolicyRepository policies,
                         BudgetIncidentRepository incidents, HeartbeatRunRepository runs,
                         AgentWakeupRequestRepository wakeups, HeartbeatRunEventRepository events) {
        this.costs = costs;
        this.policies = policies;
        this.incidents = incidents;
        this.runs = runs;
        this.wakeups = wakeups;
        this.events = events;
    }

    /** PREFLIGHT: bloqueado se ha incidente OPEN na company ou no agente. */
    @Transactional(readOnly = true)
    public boolean invocationBlocked(UUID companyId, UUID agentId) {
        if (incidents.existsByScopeTypeAndScopeIdAndStatus(ScopeType.COMPANY, companyId, IncidentStatus.OPEN)) {
            return true;
        }
        return agentId != null
                && incidents.existsByScopeTypeAndScopeIdAndStatus(ScopeType.AGENT, agentId, IncidentStatus.OPEN);
    }

    /** REATIVO: registra o custo e re-avalia os limites do escopo (company + agente). */
    @Transactional
    public CostEvent recordCost(UUID companyId, UUID agentId, UUID runId, UUID issueId,
                                String provider, String model, long costCents, int tokensIn, int tokensOut) {
        return recordCost(companyId, agentId, runId, issueId, provider, model, costCents, tokensIn, tokensOut, false);
    }

    /** Espelho com o marcador off-book (F-N5/ADR-0041, D5) — mesmo enforcement, mesma moeda de dólar. */
    @Transactional
    public CostEvent recordCost(UUID companyId, UUID agentId, UUID runId, UUID issueId,
                                String provider, String model, long costCents, int tokensIn, int tokensOut,
                                boolean offBook) {
        CostEvent event = costs.save(new CostEvent(UUID.randomUUID(), companyId, agentId, runId, issueId,
                provider, model, costCents, tokensIn, tokensOut, offBook));
        enforce(companyId, ScopeType.COMPANY, companyId);
        if (agentId != null) {
            enforce(companyId, ScopeType.AGENT, agentId);
        }
        return event;
    }

    /**
     * O ledger de ORQUESTRAÇÃO (T.4): cobra o ato NA ABERTURA (anti-enxame) na conta de quem o
     * CAUSOU ({@code causedByAgentId} = último elo da linhagem; nulo = ato humano direto). Mesma
     * mecânica reativa do dólar, moeda própria: cruzou {@code orchestration_limit_cents} → abre
     * incidente e drena o escopo até o checkpoint.
     */
    @Transactional
    public CostEvent recordOrchestration(UUID companyId, UUID causedByAgentId, UUID runId,
                                         OrchestrationAction action) {
        CostEvent event = costs.save(CostEvent.orchestration(UUID.randomUUID(), companyId,
                causedByAgentId, runId, action));
        enforceOrchestration(companyId, ScopeType.COMPANY, companyId);
        if (causedByAgentId != null) {
            enforceOrchestration(companyId, ScopeType.AGENT, causedByAgentId);
        }
        return event;
    }

    private void enforce(UUID companyId, ScopeType scope, UUID scopeId) {
        List<BudgetPolicy> applicable = policies.findByScopeTypeAndScopeId(scope, scopeId);
        if (applicable.isEmpty()) {
            return;
        }
        long spent = (scope == ScopeType.COMPANY) ? costs.sumByCompany(scopeId) : costs.sumByAgent(scopeId);
        for (BudgetPolicy policy : applicable) {
            // Guard `limitCents > 0`: um limite não-positivo NÃO é um teto de dólares executável — senão
            // `spent(0) >= limit(0)` abriria incidente na primeira emissão inócua (costCents=0), cancelando o
            // escopo (achado da revisão adversarial da F.7). A borda recusa limite ≤ 0 (422); isto blinda dado
            // pré-existente/edge e mantém "costCents=0 é inócuo" (ADR-0006/0014 Q7) incondicional.
            if (policy.getLimitCents() > 0 && spent >= policy.getLimitCents()) {
                openIncidentOnce(companyId, scope, scopeId, policy, spent, policy.getLimitCents());
                return; // um incidente por escopo basta
            }
        }
    }

    /** Espelho do enforce de dólar na moeda de orquestração (mesmo guard >0, mesmo incidente). */
    private void enforceOrchestration(UUID companyId, ScopeType scope, UUID scopeId) {
        List<BudgetPolicy> applicable = policies.findByScopeTypeAndScopeId(scope, scopeId);
        if (applicable.isEmpty()) {
            return;
        }
        long spent = (scope == ScopeType.COMPANY)
                ? costs.sumOrchestrationByCompany(scopeId) : costs.sumOrchestrationByAgent(scopeId);
        for (BudgetPolicy policy : applicable) {
            Long limit = policy.getOrchestrationLimitCents();
            if (limit != null && limit > 0 && spent >= limit) {
                openIncidentOnce(companyId, scope, scopeId, policy, spent, limit);
                return;
            }
        }
    }

    private void openIncidentOnce(UUID companyId, ScopeType scope, UUID scopeId, BudgetPolicy policy,
                                  long spent, long limit) {
        if (!incidents.existsByScopeTypeAndScopeIdAndStatus(scope, scopeId, IncidentStatus.OPEN)) {
            incidents.save(new BudgetIncident(UUID.randomUUID(), companyId, scope, scopeId,
                    policy.getId(), spent, limit));
            drainScopeWorkToCheckpoint(companyId, scope, scopeId);
        }
    }

    /**
     * Drena o escopo até o checkpoint (T.4): mata o que está em {@code QUEUED} (+ os wakeups); o
     * {@code RUNNING} termina o passo e liquida no settle — o resultado do que já foi pago não é
     * jogado fora. O preflight ({@link #invocationBlocked}) garante que nenhum sucessor nasce.
     *
     * <p><b>{@code QUEUED} NÃO é sinônimo de "não começou"</b>, e esta frase — que estava aqui e no
     * javadoc da classe — era falsa. O {@code requeueExpired} do {@code LeaseReaper} devolve a
     * {@code QUEUED} runs que <b>já rodaram</b>, com o {@code execution_lock_run_id} da Issue
     * <b>já carimbado</b> e {@code attempt > 1} — fato que o próprio {@code HeartbeatRunStore.claim}
     * reconhece (ele checa {@code attempt == 1} justamente para não cobrar {@code RUN_OPEN} duas
     * vezes). Cancelar aqui, portanto, <b>pode deixar Issues carimbadas com um run que nunca mais
     * roda</b>: este é o produtor <b>em lote</b> de lock órfão, e é o mais provável de disparar, já
     * que o run caro é justamente o que trava e perde o lease.
     *
     * <p><b>Rastro, sim; liquidação por-run, NÃO — e a razão é TRANSACIONAL.</b> O caminho em lote
     * grava {@code RUN_CANCELLED} para cada alvo (sem isso, o cancelamento em massa não deixava
     * <b>uma linha</b> de evidência: o {@code recordEvent} mora no {@code HeartbeatRunStore}, que
     * este caminho não usa). Mas <b>não</b> liquida as Issues aqui, e o motivo não é estilo: este
     * método roda dentro do {@code @Transactional} de {@code HeartbeatRunStore.claim} — via
     * {@code recordOrchestration} → {@code enforce} → {@link #openIncidentOnce} — de modo que um
     * settle por-run aninharia N liquidações de Issue (cada uma com sua gravação de activity log)
     * <b>dentro da transação de claim de OUTRO run</b>, e um throw derrubaria esse claim. Quem
     * liquida estas Issues é o {@code LockOrfaoReaper}, fora de qualquer transação alheia. (O ciclo
     * de bean {@code HeartbeatRunStore} → {@code BudgetService} também existe, mas é contornável —
     * {@code @Lazy}, um terceiro bean, ou injetar o repositório como aqui; a razão transacional não
     * tem contorno.)
     */
    private void drainScopeWorkToCheckpoint(UUID companyId, ScopeType scope, UUID scopeId) {
        List<HeartbeatRun> alvos = scope == ScopeType.COMPANY
                ? runs.findByCompanyIdAndStatusIn(companyId, CANCELLABLE_RUNS)
                : runs.findByAgentAndStatuses(scopeId, CANCELLABLE_RUNS);
        List<UUID> alvoIds = alvos.stream().map(HeartbeatRun::getId).toList();

        if (scope == ScopeType.COMPANY) {
            runs.cancelByCompany(companyId, CANCELLABLE_RUNS, RunStatus.CANCELLED);
            wakeups.cancelByCompany(companyId, CANCELLABLE_WAKEUPS, WakeupStatus.CANCELLED);
        } else {
            runs.cancelByAgent(scopeId, CANCELLABLE_RUNS, RunStatus.CANCELLED);
            wakeups.cancelByAgent(scopeId, CANCELLABLE_WAKEUPS, WakeupStatus.CANCELLED);
        }

        // Só grava para quem REALMENTE virou CANCELLED: entre o select e o UPDATE em massa, outro
        // nó pode ter reivindicado um alvo (QUEUED -> RUNNING), e esse escapou do cancel. Registrar
        // o evento para ele seria escrever no rastro um fato que não aconteceu.
        String motivo = "budget hard-stop: drain do escopo " + scope + " " + scopeId;
        for (HeartbeatRun alvo : runs.findAllById(alvoIds)) {
            if (alvo.getStatus() == RunStatus.CANCELLED) {
                events.save(new HeartbeatRunEvent(UUID.randomUUID(), alvo.getId(), alvo.getCompanyId(),
                        "RUN_CANCELLED", motivo));
            }
        }
    }

    /** Override (governanca): resolve o incidente e destrava o escopo. */
    @Transactional
    public void resolveIncident(UUID companyId, UUID incidentId) {
        BudgetIncident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new NotFoundException("Incidente nao encontrado: " + incidentId));
        if (!incident.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Incidente nao pertence a company " + companyId);
        }
        incident.resolve();
    }

    @Transactional
    public BudgetPolicy createPolicy(UUID companyId, ScopeType scopeType, UUID scopeId, long limitCents, Long warnCents) {
        return policies.save(new BudgetPolicy(UUID.randomUUID(), companyId, scopeType, scopeId, limitCents, warnCents));
    }

    /** Policy com teto de ORQUESTRAÇÃO (T.4). limitCents=0 deixa o teto de dólar inerte (guard >0). */
    @Transactional
    public BudgetPolicy createPolicy(UUID companyId, ScopeType scopeType, UUID scopeId, long limitCents,
                                     Long warnCents, Long orchestrationLimitCents) {
        BudgetPolicy policy = new BudgetPolicy(UUID.randomUUID(), companyId, scopeType, scopeId,
                limitCents, warnCents);
        policy.limitarOrquestracao(orchestrationLimitCents);
        return policies.save(policy);
    }

    @Transactional(readOnly = true)
    public long spentForCompany(UUID companyId) {
        return costs.sumByCompany(companyId);
    }

    @Transactional(readOnly = true)
    public long spentForAgent(UUID agentId) {
        return costs.sumByAgent(agentId);
    }

    @Transactional(readOnly = true)
    public long orchestrationSpentForCompany(UUID companyId) {
        return costs.sumOrchestrationByCompany(companyId);
    }

    @Transactional(readOnly = true)
    public long orchestrationSpentForAgent(UUID agentId) {
        return costs.sumOrchestrationByAgent(agentId);
    }

    @Transactional(readOnly = true)
    public List<BudgetIncident> incidentsFor(UUID companyId) {
        return incidents.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    /**
     * A superfície de leitura do gasto off-book (F-N5/ADR-0041, D5): os lançamentos de TOKEN de um
     * run, na company dona — inclui o marcador {@code offBook} de cada linha. Company errada (ou
     * run de outra company) devolve vazio — não vaza lançamento entre companies.
     */
    @Transactional(readOnly = true)
    public List<CostEvent> costEventsForRun(UUID companyId, UUID runId) {
        return costs.findByCompanyIdAndRunIdAndOrchestrationActionIsNullOrderByCreatedAtAsc(companyId, runId);
    }
}
