package com.orquestrador.execution;

import org.springframework.data.domain.Pageable;
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

    /**
     * <b>A PROCEDÊNCIA de um {@code runId}</b> (Fatia I-ter): este Run existe, é <b>desta</b>
     * company, é <b>deste</b> agente e é <b>desta</b> Issue. O predicado que
     * {@code IssueService.checkout}/{@code .complete} exigem antes de deixar um agente carimbar ou
     * descarimbar o {@code execution_lock_run_id} — a coluna que <b>não tem FK</b>, e por isso
     * aceitava qualquer UUID que o cliente inventasse.
     *
     * <p><b>As quatro componentes fecham quatro furos distintos</b>, e nenhuma é redundante:
     * {@code id} recusa o runId sem linha (o pior lock órfão, e o que o {@code /checkout} sem corpo
     * fabricava sozinho); {@code companyId} recusa o run alheio cross-tenant; {@code agentId} recusa
     * agir em nome de run de outro agente (o furo do {@code /complete}, que autenticava e descartava
     * a identidade); e {@code issueId} recusa o <b>6º produtor, intra-company</b> — carimbar a Issue
     * Y com o runId de um Run VIVO da Issue Z da mesma company, que a rede do
     * {@code LockOrfaoReaper} <b>por construção não alcança</b>, porque o Run nomeado tem futuro.
     *
     * <p><b>Deny-by-default, e é ele quem faz a cerca fechar sozinha:</b> um Run que não existe
     * responde {@code false} sem caso especial — a mesma forma negativa que a Fatia I-bis provou
     * ser a correta em {@code findComLockDeRunTerminal}.
     *
     * <p><b>O motor REAL satisfaz isto por construção</b>, e é o que torna a cerca barata: o
     * {@code AdapterRunExecutor} chama o checkout com {@code runContext.companyId()},
     * {@code .agentId()}, {@code .issueId()} e {@code .runId()} — os quatro campos do MESMO Run que
     * ele está executando. A cerca não estreita nenhum caminho legítimo; só nomeia o invariante que
     * o motor já cumpria e o balcão HTTP não exigia.
     */
    boolean existsByIdAndCompanyIdAndAgentIdAndIssueId(UUID id, UUID companyId, UUID agentId, UUID issueId);

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

    /**
     * Circuit-breaker de erro repetido (ADR-0018 teto (c), R18-B): os desfechos de EXECUCAO mais
     * recentes do par agente×issue, do mais novo ao mais velho. O caller passa o filtro de status
     * (so FAILED/SUCCEEDED — abortos administrativos e estados nao-terminais ficam de fora) e o
     * limite ({@link Pageable}); o breaker abre quando os N primeiros vierem todos FAILED. Derivado
     * do historico ja persistido, sem coluna nem tabela nova.
     */
    @Query("""
            select r from HeartbeatRun r
             where r.agentId = :agentId and r.issueId = :issueId and r.status in :desfechos
             order by r.createdAt desc
            """)
    List<HeartbeatRun> findRecentOutcomesByPair(@Param("agentId") UUID agentId,
                                                @Param("issueId") UUID issueId,
                                                @Param("desfechos") Collection<RunStatus> desfechos,
                                                Pageable pageable);

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

    /** Pulso do worker (ADR-0005): estende o lease so se o dono AINDA possui o Run (CAS por dono). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update HeartbeatRun r
               set r.leaseExpiresAt = :exp
             where r.id = :id and r.status = :running and r.leaseOwner = :owner
            """)
    int renewLease(@Param("id") UUID id, @Param("running") RunStatus running,
                   @Param("owner") String owner, @Param("exp") Instant exp);

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
