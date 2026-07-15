package com.orquestrador.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    List<Decision> findByCompanyIdOrderByDecidedAtDesc(UUID companyId);

    /**
     * Maior #N já alocado nesta company (0 se nenhum) — a base do MAX+1 do record (C4, clone da
     * ADR-0033). Só é seguro sob o lock da linha da company ({@code CompanyRepository#findByIdForUpdate}).
     */
    @Query("select coalesce(max(d.seq), 0) from Decision d where d.companyId = :companyId")
    int maxSeqByCompany(@Param("companyId") UUID companyId);

    /** O endereço humano #N → a linha (company-scoped: o espaço de endereços nunca vaza). */
    Optional<Decision> findByCompanyIdAndSeq(UUID companyId, int seq);

    /**
     * As Decisões ancoradas numa Issue, em ordem de endereço (R19-A, fecho v1): o motivo do REJECT
     * do review ({@code anchoredToReview} grava o {@code issue_id}) e as do caso B
     * ({@code anchoredToIssue}). É a fonte da dieta DIRECIONADA que o executor recebe no re-run —
     * um caminho NOVO e ESTREITO (só a issue do run), fora do trilho JIT selado do {@link MemoryQuery}
     * (que serve grau=PRINCIPIO || tipo=DIRECAO); por isso lê o repositório cru, não a fronteira C10.b.
     * Company-scoped: o feedback de uma company nunca vaza no workspace de outra.
     */
    List<Decision> findByCompanyIdAndIssueIdOrderBySeqAsc(UUID companyId, UUID issueId);
}
