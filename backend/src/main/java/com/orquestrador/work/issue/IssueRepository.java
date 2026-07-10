package com.orquestrador.work.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueRepository extends JpaRepository<Issue, UUID> {

    List<Issue> findByCompanyId(UUID companyId);

    /**
     * Maior #N ja alocado nesta company (0 se nenhum) — a base do MAX+1 do create (ADR-0033).
     * Espelha {@link com.orquestrador.org.roletemplate.RoleTemplateVersionRepository#maxVersion}
     * — {@code max(seq)+1} (nunca {@code count+1}, que reciclaria numero apos qualquer gap).
     * So e seguro sob o lock da linha da company ({@code CompanyRepository#findByIdForUpdate}).
     */
    @Query("select coalesce(max(i.seq), 0) from Issue i where i.companyId = :companyId")
    int maxSeqByCompany(@Param("companyId") UUID companyId);

    /**
     * F.14 (ADR-0028): as Issues de uma company num conjunto de status — a base da varredura do
     * {@code RowRetentionReaper} (terminais DONE/CANCELLED). O gate de retenção (updated_at vencido)
     * é da {@code RowRetentionPolicy}, não da query; aqui só afunila por status (a Issue é a tabela
     * pequena — o que explode são as rows-filhas).
     */
    List<Issue> findByCompanyIdAndStatusIn(UUID companyId, Collection<IssueStatus> statuses);

    Optional<Issue> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * Resolve o #N humano para a Issue (caso B, ADR-0034/M1 — o lookup que a ADR-0033 §4 já
     * previa): "a task #12 ficou ruim" chega como número, e o roteamento da correção começa
     * aqui. O índice {@code uq_issue_company_seq} (V23) é o caminho natural.
     */
    Optional<Issue> findByCompanyIdAndSeq(UUID companyId, int seq);

    /**
     * F.12 (ADR-0026): as Issues que JÁ rodaram (assignee fixado no checkout ⇒ têm workspace) — a
     * base da DERIVAÇÃO do {@code WorkspaceReaper}. Deriva o ref esperado de cada uma e intersecta
     * com o mundo listado pelo alvo; um ref no mundo fora deste conjunto é órfão (só WARN). Inclui
     * todos os status (uma Issue viva/reaberta mantém o assignee) — o gate terminal+retenção é do
     * reaper, não da query. Varredura de sistema (sem company_id): banco inteiro &lt; 10 MB.
     */
    List<Issue> findByAssigneeAgentIdIsNotNull();

    /** Mission control (F.0): Issues estacionadas (BLOCKED) marcam o card do assignee como bloqueado. */
    List<Issue> findByCompanyIdAndStatus(UUID companyId, IssueStatus status);

    /**
     * Checkout ATOMICO: o banco (nao a aplicacao) arbitra a corrida. Um unico UPDATE guardado.
     * Linhas afetadas == 1 -> o run pegou a Issue; == 0 -> 409 (outro agente/run ja a tem,
     * ou ela nao esta mais em estado pegavel). NUNCA fazer retry de um 0.
     *
     * <p>Idempotente para o mesmo (agente, run): reentrar com o proprio runId casa o WHERE.
     * Um run diferente nao rouba uma trava ativa (a recuperacao de trava morta vem com o
     * heartbeat/lease, fatia 2). A clausula e equivalente ao guarded-UPDATE do Paperclip.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'IN_PROGRESS',
                   assignee_agent_id = :agentId,
                   execution_lock_run_id = :runId,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status in ('OPEN', 'IN_PROGRESS')
               and (assignee_agent_id is null or assignee_agent_id = :agentId)
               and (execution_lock_run_id is null or execution_lock_run_id = :runId)
            """, nativeQuery = true)
    int tryCheckout(@Param("issueId") UUID issueId,
                    @Param("companyId") UUID companyId,
                    @Param("agentId") UUID agentId,
                    @Param("runId") UUID runId);

    /**
     * Conclusao: so o run que detem a trava conclui (libera a trava, status DONE).
     * Linhas == 1 -> concluido; == 0 -> 409 (nao era o detentor da trava).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'DONE',
                   execution_lock_run_id = null,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and execution_lock_run_id = :runId
            """, nativeQuery = true)
    int tryComplete(@Param("issueId") UUID issueId,
                    @Param("companyId") UUID companyId,
                    @Param("runId") UUID runId);

    /**
     * ESTACIONA a Issue de alto risco para revisão (review gate, ADR-0012): espelho do {@link #tryComplete}
     * mas status -> PENDING_REVIEW. O MESMO guard {@code execution_lock_run_id = :runId} vale no branch de
     * review: run que não detinha a Issue casa 0 linhas → no-op (vítima intacta). Libera o lock — Issue em
     * revisão não tem run em voo (não é pegável, não gasta budget).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'PENDING_REVIEW',
                   execution_lock_run_id = null,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and execution_lock_run_id = :runId
               and status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int trySubmitForReview(@Param("issueId") UUID issueId,
                           @Param("companyId") UUID companyId,
                           @Param("runId") UUID runId);

    /**
     * ESTACIONA a Issue após um Run FAILED (recovery, ADR-0011): espelho do {@link #tryComplete} mas
     * status -> BLOCKED. O MESMO guard {@code execution_lock_run_id = :runId} garante que SÓ o run
     * detentor bloqueia — um run que falhou ANTES do checkout (ou perdeu a corrida) casa 0 linhas → no-op,
     * jamais bloqueando uma Issue-vítima que não possuía. Libera o lock (recovery é do board, não do run).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'BLOCKED',
                   execution_lock_run_id = null,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and execution_lock_run_id = :runId
               and status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int tryBlock(@Param("issueId") UUID issueId,
                 @Param("companyId") UUID companyId,
                 @Param("runId") UUID runId);

    /** Decisão do board (ADR-0012): PENDING_REVIEW -> DONE. rows==0 -> não estava em revisão (409). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'DONE',
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'PENDING_REVIEW'
            """, nativeQuery = true)
    int tryApprove(@Param("issueId") UUID issueId, @Param("companyId") UUID companyId);

    /**
     * Decisão do board (ADR-0012): PENDING_REVIEW -> BLOCKED (parked; o recovery é 100% o
     * unblock {REOPEN|CANCEL} do §7 — o feedback entra editando a Issue antes do REOPEN).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'BLOCKED',
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'PENDING_REVIEW'
            """, nativeQuery = true)
    int tryReject(@Param("issueId") UUID issueId, @Param("companyId") UUID companyId);

    /** Destrava BLOCKED -> OPEN mantendo o assignee (o próximo wakeup re-executa com o mesmo agente). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'OPEN',
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'BLOCKED'
            """, nativeQuery = true)
    int tryReopen(@Param("issueId") UUID issueId, @Param("companyId") UUID companyId);

    /** Destrava BLOCKED -> CANCELLED (encerra a Issue de vez). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'CANCELLED',
                   execution_lock_run_id = null,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'BLOCKED'
            """, nativeQuery = true)
    int tryCancel(@Param("issueId") UUID issueId, @Param("companyId") UUID companyId);
}
