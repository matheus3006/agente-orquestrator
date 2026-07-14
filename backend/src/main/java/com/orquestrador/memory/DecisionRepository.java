package com.orquestrador.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    List<Decision> findByCompanyIdOrderByDecidedAtDesc(UUID companyId);

    /** A projeção da carta lê SÓ direção, em ordem cronológica (determinística). */
    List<Decision> findByCompanyIdAndTipoOrderByDecidedAtAscIdAsc(UUID companyId, DecisionTipo tipo);

    /**
     * Maior #N já alocado nesta company (0 se nenhum) — a base do MAX+1 do record (C4, clone da
     * ADR-0033). Só é seguro sob o lock da linha da company ({@code CompanyRepository#findByIdForUpdate}).
     */
    @Query("select coalesce(max(d.seq), 0) from Decision d where d.companyId = :companyId")
    int maxSeqByCompany(@Param("companyId") UUID companyId);

    /** O endereço humano #N → a linha (company-scoped: o espaço de endereços nunca vaza). */
    Optional<Decision> findByCompanyIdAndSeq(UUID companyId, int seq);
}
