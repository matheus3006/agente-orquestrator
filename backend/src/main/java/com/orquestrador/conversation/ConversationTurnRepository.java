package com.orquestrador.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, UUID> {

    List<ConversationTurn> findByConversationIdAndCompanyIdOrderByCreatedAtAsc(UUID conversationId, UUID companyId);

    /**
     * CLAIM guardado do turno mais antigo PENDING do agente (espelha o checkout da Issue): só marca
     * {@code run_id} se ainda não tem dono — OU se o dono é ESTE run (reaper requeia o MESMO runId
     * após lease expirado; o re-claim precisa ser idempotente). Nativa pelo {@code limit 1} no
     * subselect (H2 em modo PG aceita).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update conversation_turn set run_id = :runId
            where id = (select id from conversation_turn
                        where agent_id = :agentId and status = 'PENDING'
                          and (run_id is null or run_id = :runId)
                        order by created_at asc, id asc limit 1)
            """)
    int claimOldestPending(@Param("agentId") UUID agentId, @Param("runId") UUID runId);

    Optional<ConversationTurn> findFirstByRunIdAndStatusOrderByCreatedAtAsc(UUID runId, TurnStatus status);

    boolean existsByAgentIdAndStatus(UUID agentId, TurnStatus status);
}
