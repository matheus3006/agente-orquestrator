package com.orquestrador.governance.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CostEventRepository extends JpaRepository<CostEvent, UUID> {

    /**
     * Gasto em DOLAR do escopo = SUM do ledger (verdade). coalesce p/ 0 quando nao ha eventos.
     * Filtra a moeda: lancamentos de ORQUESTRACAO (T.4) nunca entram no teto de dolar.
     */
    @Query("""
            select coalesce(sum(c.costCents), 0) from CostEvent c
             where c.companyId = :companyId and c.orchestrationAction is null
            """)
    long sumByCompany(@Param("companyId") UUID companyId);

    @Query("""
            select coalesce(sum(c.costCents), 0) from CostEvent c
             where c.agentId = :agentId and c.orchestrationAction is null
            """)
    long sumByAgent(@Param("agentId") UUID agentId);

    /** Gasto de ORQUESTRACAO do escopo (T.4) — a outra moeda, contra orchestration_limit_cents. */
    @Query("""
            select coalesce(sum(c.costCents), 0) from CostEvent c
             where c.companyId = :companyId and c.orchestrationAction is not null
            """)
    long sumOrchestrationByCompany(@Param("companyId") UUID companyId);

    @Query("""
            select coalesce(sum(c.costCents), 0) from CostEvent c
             where c.agentId = :agentId and c.orchestrationAction is not null
            """)
    long sumOrchestrationByAgent(@Param("agentId") UUID agentId);

    /** Emissões de custo de um Run (observabilidade de tokens por-run; ADR-0014/0015). */
    List<CostEvent> findByRunId(UUID runId);

    /**
     * Os lançamentos de TOKEN (exclui orquestração, T.4/V19 — moeda própria) de um run, na company
     * dona — a superfície de leitura do gasto off-book (F-N5/ADR-0041, D5). Ordem de gravação.
     */
    List<CostEvent> findByCompanyIdAndRunIdAndOrchestrationActionIsNullOrderByCreatedAtAsc(
            UUID companyId, UUID runId);

    /** Os atos de orquestração de um tipo na company (observabilidade do ledger T.4). */
    List<CostEvent> findByCompanyIdAndOrchestrationAction(UUID companyId, OrchestrationAction action);
}
