package com.orquestrador.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HeartbeatRunRepository extends JpaRepository<HeartbeatRun, UUID> {

    List<HeartbeatRun> findTop50ByStatusOrderByCreatedAtAsc(RunStatus status);

    List<HeartbeatRun> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    /** Guard do review gate (ADR-0012): a review só referencia run REAL desta company (deny-by-default). */
    boolean existsByIdAndCompanyId(UUID id, UUID companyId);

    /** Leitura company-led de um run (transcript do board): fora da company = ausente (404). */
    Optional<HeartbeatRun> findByIdAndCompanyId(UUID id, UUID companyId);

    /** Ledger do Issue detail (F.0): toda tentativa da Issue, mais recente primeiro. */
    List<HeartbeatRun> findByIssueIdAndCompanyIdOrderByCreatedAtDesc(UUID issueId, UUID companyId);

    /** Runs em voo/na fila da company (mission control): invariante 1-run-ativo-por-agente. */
    List<HeartbeatRun> findByCompanyIdAndStatusIn(UUID companyId, Collection<RunStatus> statuses);

    /**
     * F.15 (adendo à ADR-0028): a 2ª passada do {@code RowRetentionReaper} — os Runs que são TURNOS
     * de conversa do CEO ({@code issue_id} NULL) VENCIDOS, para coletar seu rastro em
     * {@code heartbeat_run_event}. Mira SÓ via {@code conversation_turn.run_id} (D4 — não
     * {@code issue_id IS NULL}, que também pega wakeup manual/aprovação); carimba pelo
     * {@code COALESCE(finished_at, created_at)} do Run (D2 — o turno "fantasma" sem {@code finished_at}
     * varre pelo {@code created_at}; o turno rodando/recente tem {@code created_at} recente e escapa,
     * M5). {@code exists} deduplica o coalescing (1 Run → N turnos → 1 id).
     */
    @Query("""
            select r.id from HeartbeatRun r
             where r.companyId = :companyId
               and coalesce(r.finishedAt, r.createdAt) < :cutoff
               and exists (select 1 from ConversationTurn t where t.runId = r.id)
            """)
    List<UUID> findConversationTurnRunIdsExpired(@Param("companyId") UUID companyId,
                                                 @Param("cutoff") Instant cutoff);

    /** O run mais recente de um agente (rastro do card quando ocioso). */
    Optional<HeartbeatRun> findFirstByCompanyIdAndAgentIdOrderByCreatedAtDesc(UUID companyId, UUID agentId);

    @Query("select r from HeartbeatRun r where r.agentId = :agentId and r.status in :statuses")
    List<HeartbeatRun> findByAgentAndStatuses(@Param("agentId") UUID agentId,
                                              @Param("statuses") Collection<RunStatus> statuses);

    /** Fan-out do time (T.4): quantos runs dos MEMBROS do time estao nos statuses dados. */
    @Query("""
            select count(r) from HeartbeatRun r, Agent a
             where a.id = r.agentId and a.teamId = :teamId and r.status in :statuses
            """)
    long countRunsOfTeam(@Param("teamId") UUID teamId, @Param("statuses") Collection<RunStatus> statuses);

    /** Claim por CAS: so vira RUNNING quem ainda esta QUEUED. rows==1 -> claimed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update HeartbeatRun r
               set r.status = :running, r.leaseOwner = :owner, r.leaseExpiresAt = :exp,
                   r.attempt = r.attempt + 1, r.startedAt = :now
             where r.id = :id and r.status = :queued
            """)
    int claim(@Param("id") UUID id, @Param("queued") RunStatus queued, @Param("running") RunStatus running,
              @Param("owner") String owner, @Param("exp") Instant exp, @Param("now") Instant now);

    /** Finaliza so o detentor do lease (success: error=null; failure: error preenchido). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update HeartbeatRun r
               set r.status = :to, r.finishedAt = :now, r.lastError = :error
             where r.id = :id and r.status = :from and r.leaseOwner = :owner
            """)
    int finalizeRun(@Param("id") UUID id, @Param("from") RunStatus from, @Param("to") RunStatus to,
                    @Param("owner") String owner, @Param("now") Instant now, @Param("error") String error);

    /** Reaper: Runs com lease expirado voltam para a fila (idempotente). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update HeartbeatRun r
               set r.status = :to, r.leaseOwner = null, r.leaseExpiresAt = null
             where r.status = :from and r.leaseExpiresAt < :now
            """)
    int requeueExpired(@Param("from") RunStatus from, @Param("to") RunStatus to, @Param("now") Instant now);

    /** Cancela um Run individual se ainda estiver QUEUED (usado pelo preflight de budget). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HeartbeatRun r set r.status = :to where r.id = :id and r.status = :from")
    int cancelIfStatus(@Param("id") UUID id, @Param("from") RunStatus from, @Param("to") RunStatus to);

    /** Hard-stop reativo: cancela os Runs do escopo (company). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HeartbeatRun r set r.status = :to where r.companyId = :companyId and r.status in :from")
    int cancelByCompany(@Param("companyId") UUID companyId,
                        @Param("from") Collection<RunStatus> from, @Param("to") RunStatus to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update HeartbeatRun r set r.status = :to where r.agentId = :agentId and r.status in :from")
    int cancelByAgent(@Param("agentId") UUID agentId,
                      @Param("from") Collection<RunStatus> from, @Param("to") RunStatus to);
}
