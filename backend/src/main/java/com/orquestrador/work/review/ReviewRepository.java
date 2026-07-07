package com.orquestrador.work.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries SEMPRE lideradas por company_id (deny-by-default): review de uma company nunca
 * aparece em consulta de outra.
 */
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByCompanyIdAndIssueId(UUID companyId, UUID issueId);

    List<Review> findByCompanyIdAndStatusOrderByRequestedAt(UUID companyId, ReviewStatus status);

    /** O ciclo aberto da Issue (máx. 1 PENDING por Issue — uq_review_pending na V11). */
    Optional<Review> findByCompanyIdAndIssueIdAndStatus(UUID companyId, UUID issueId, ReviewStatus status);

    /**
     * F.14 (ADR-0028): os {@code run_id} fixados por review nesta Issue — os pins que NÃO graduam
     * para fora (a evidência "R aprovou a v3"). O {@code RowRetentionReaper} usa este conjunto na
     * {@code RowRetentionPolicy} para preservar as versões de artifact referenciadas por uma review,
     * mesmo superseded. FK-safe: a review referencia {@code heartbeat_run}, não o artifact.
     */
    @Query("select r.runId from Review r where r.companyId = :companyId and r.issueId = :issueId")
    List<UUID> findRunIdsByCompanyIdAndIssueId(@Param("companyId") UUID companyId,
                                               @Param("issueId") UUID issueId);
}
