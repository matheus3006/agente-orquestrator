package com.orquestrador.work.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * O parecer consultivo entra por UPDATE GUARDADO (hardening do PR #50, achado 🔴 2): a cerca do
     * primeiro-vence é do BANCO, não do read-then-write da aplicação. {@code rows==1} → este
     * chamador gravou; {@code rows==0} → o ciclo já não aceita parecer (já tinha um, OU o dono
     * decidiu no meio) → o gate vira aviso contado.
     *
     * <p><b>DUAS cercas, não uma</b> (conferência dos PRs #51/#52, 🟠 1): {@code parecer is null}
     * guarda a DUPLICATA; {@code status = 'PENDING'} guarda o CICLO DE VIDA. Só a primeira existia,
     * e a ADR-0048 apresentava os dois ramos como simétricos ("hoje os dois ramos têm CAS de
     * banco") — mas o PROMOVIDO guardava o ciclo ({@code where status='PENDING_REVIEW'}) e este
     * guardava só a duplicata. A janela entre {@code cicloAbertoAncorado} e este UPDATE contém
     * {@code exigirGestorTitularComAlvoNoBraco}, com 4 round-trips ao banco: se o dono decidisse o
     * ciclo aí dentro, {@code parecer is null} seguia verdadeiro e este UPDATE gravava parecer
     * consultivo num ciclo já APPROVED/REJECTED — devolvendo {@code true}, emitindo
     * {@code REVIEW_PARECER_REGISTRADO} e deixando {@code parecer_em > decided_at} sem aviso nenhum.
     *
     * <p>Idioma da casa ({@code tryCheckout}, {@code tryApprove}, {@code tryAbsorver}): quem arbitra
     * a corrida é o {@code rows affected}, nunca a aplicação. O que havia antes era read-then-write
     * puro — {@code Review} não tem {@code @Version} e o UPDATE do JPA é {@code where id = ?}; em
     * READ COMMITTED duas transações liam {@code parecer IS NULL} e a segunda SOBRESCREVIA em
     * silêncio, devolvendo "gravei". Índice parcial (o remédio da V37/ADR-0047) não se aplica aqui:
     * lá o furo criava LINHA nova; aqui é coluna de linha que já existe, e unique não expressa
     * "no máximo um preenchimento".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update review
               set parecer = :parecer,
                   parecer_por_agent_id = :gestorAgentId,
                   parecer_em = now()
             where id = :reviewId
               and company_id = :companyId
               and status = 'PENDING'
               and parecer is null
            """, nativeQuery = true)
    int tryRegistrarParecer(@Param("reviewId") UUID reviewId,
                            @Param("companyId") UUID companyId,
                            @Param("gestorAgentId") UUID gestorAgentId,
                            @Param("parecer") String parecer);
}
