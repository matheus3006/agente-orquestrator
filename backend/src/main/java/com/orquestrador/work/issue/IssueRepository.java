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
     * A issue ancorada na conversa (ADR-0037, D4/M8 — a direção INVERSA da ancoragem): o APPROVE
     * do {@code CREATE_TEAM} chega com {@code approval.conversationId} e a retoma precisa saber
     * QUAL issue retriar. 1:1 por construção (assunto "#N — <título>" único); o findFirst é
     * cinto contra dados anômalos.
     */
    Optional<Issue> findFirstByCompanyIdAndConversationId(UUID companyId, UUID conversationId);

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
     * <b>As Issues com LOCK ÓRFÃO</b> — {@code IN_PROGRESS} carimbadas com um Run que não roda mais
     * (Fatia I, peça 2). A base da varredura do {@code LockOrfaoReaper}; o caller passa quais status
     * de Run contam como "não roda mais", porque essa escolha é decisão de desenho, não da query
     * ({@code INTERRUPTED} fica de fora — ver o javadoc do reaper).
     *
     * <p>Varredura de SISTEMA, sem {@code company_id} — o órfão não sabe de quem é, e {@code issue} é
     * a tabela pequena da casa (o que explode são as rows-filhas; mesmo recorte do
     * {@code findByAssigneeAgentIdIsNotNull} do {@code WorkspaceReaper}).
     *
     * <p><b>Não pede índice em {@code execution_lock_run_id}, e isso foi medido, não presumido:</b>
     * a coluna aqui é a ORIGEM do join, nunca o critério de busca — quem afunila é
     * {@code i.status = IN_PROGRESS}, e o {@code exists} casa por {@code r.id}, que é a CHAVE
     * PRIMÁRIA de {@code heartbeat_run}. Uma busca <i>por</i> {@code execution_lock_run_id} é que
     * pediria índice, e não existe nenhuma — nem aqui nem no resto do repositório.
     */
    @Query("""
            select i from Issue i
             where i.status = :emProgresso
               and i.executionLockRunId is not null
               and exists (select 1 from HeartbeatRun r
                            where r.id = i.executionLockRunId and r.status in :runsQueNaoRodamMais)
            """)
    List<Issue> findComLockDeRunTerminal(
            @Param("emProgresso") IssueStatus emProgresso,
            @Param("runsQueNaoRodamMais") Collection<com.orquestrador.execution.RunStatus> runsQueNaoRodamMais);

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
     * A ATRIBUIÇÃO da absorção (ADR-0037, M5): entrega a issue ÓRFÃ à mesa do arquiteto num
     * UPDATE guardado — só {@code assignee is null} e {@code OPEN} casam (0 linhas → a guarda
     * D9.3: já tem dona/saiu de OPEN, o roteador AVISA em vez de re-atribuir; cobre settle
     * reexecutado E corrida com checkout). NÃO mexe em status/lock: a issue segue OPEN, e o
     * {@link #tryCheckout} ({@code assignee is null or = :agentId}) passa a aceitar SÓ o arquiteto.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set assignee_agent_id = :arquitetoId,
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'OPEN'
               and assignee_agent_id is null
            """, nativeQuery = true)
    int tryAbsorver(@Param("issueId") UUID issueId,
                    @Param("companyId") UUID companyId,
                    @Param("arquitetoId") UUID arquitetoId);

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

    /**
     * REABRE uma entrega já APROVADA: {@code DONE -> BLOCKED} (ADR-0049, decisão do dono de
     * 2026-07-20). O único UPDATE guardado da casa que aceita {@code DONE} como estado-ORIGEM —
     * até aqui {@code DONE} só aparecia como alvo, o que fazia dele um SINK e tornava o APPROVE
     * irreversível.
     *
     * <p>Por que {@code BLOCKED} e não {@code OPEN}: é exatamente onde um REJECT deixa a Issue, e
     * dali o {@code unblock {REOPEN|CANCEL}} do §7 — que já existe, já é guardado e já é conhecido
     * — dá ao dono a escolha entre refazer e encerrar. Reabrir CONVERGE para o caminho que já
     * existe em vez de inventar um segundo jeito de estar "em revisão".
     *
     * <p>rows==0 → a Issue não estava DONE (409). Não mexe em {@code execution_lock_run_id}: quem
     * chega a DONE pelo review gate já teve o lock liberado no {@code trySubmitForReview}.
     *
     * <p><b>"APROVADA" aqui é DONE, e o escopo é MAIOR que "um APPROVE de review"</b> (conferência
     * dos PRs #51/#52, 🔵 — o nome, a mensagem de 409 e a ADR-0049 descreviam só "desfazer um
     * APPROVE"). O guard é {@code status='DONE'} e nada mais, então isto também reabre a issue de
     * risco BAIXO que chegou a DONE por {@code tryComplete} e <b>nunca teve review</b> —
     * {@code review_id} é nullable e o caminho funciona.
     *
     * <p><b>Isso é deliberado, não descuido.</b> No vocabulário da casa {@code DONE} É a entrega
     * ACEITA: pelo revisor quando o risco ≥ limiar, ou pela própria política de risco quando está
     * abaixo dele (ADR-0012 — "risco baixo dispensa review" é uma decisão de aceite, não ausência
     * de uma). Reabrir vale sobre entrega aceita, e as duas formas de aceitar contam. Estreitar o
     * guard para "só quem teve ciclo APPROVED" tiraria do dono a válvula sobre metade das
     * entregas, sem ganho de segurança nenhum.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update issue
               set status = 'BLOCKED',
                   updated_at = now()
             where id = :issueId
               and company_id = :companyId
               and status = 'DONE'
            """, nativeQuery = true)
    int tryReabrirAprovada(@Param("issueId") UUID issueId, @Param("companyId") UUID companyId);

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
