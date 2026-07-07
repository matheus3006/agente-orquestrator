package com.orquestrador.execution;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface HeartbeatRunEventRepository extends JpaRepository<HeartbeatRunEvent, UUID> {

    List<HeartbeatRunEvent> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /**
     * Página do StepTrace por offset de CONTAGEM (F.0): a tabela é append-only, então o offset é
     * estável — o poll incremental pede sempre "do nextOffset em diante". Ordem total estável via
     * desempate por id (created_at pode colidir no microssegundo; a ordem intra-colisão é
     * arbitrária porém FIXA, o offset nunca pula nem duplica step). Servida por idx_run_event_run.
     */
    @Query(value = """
            select * from heartbeat_run_event
             where run_id = :runId
             order by created_at asc, id asc
            offset :offset rows fetch first :limit rows only
            """, nativeQuery = true)
    List<HeartbeatRunEvent> findStepsPage(@Param("runId") UUID runId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    /** O rabo do trace (mini-terminal do card): os últimos N na MESMA ordem total do findStepsPage. */
    List<HeartbeatRunEvent> findTop5ByRunIdOrderByCreatedAtDescIdDesc(UUID runId);

    /**
     * F.14 (ADR-0028): uma PÁGINA de ids de evento dos runs dados — o chunk do DELETE do
     * {@code RowRetentionReaper}. run_event é count-heavy (o firehose do StepTrace); coletamos por
     * páginas de ids em vez de materializar milhões numa transação só. Serve as DUAS passadas do
     * reaper: os runs de Issue terminal-vencida (F.14) e os runs de turnos de conversa do CEO
     * (F.15 — {@code issue_id} NULL, mirados por {@code conversation_turn.run_id}).
     */
    @Query("select e.id from HeartbeatRunEvent e where e.runId in :runIds")
    List<UUID> findIdsByRunIdIn(@Param("runIds") Collection<UUID> runIds, Pageable pageable);

    /** F.14 (ADR-0028): quantos eventos os runs dados têm — o antes/depois da prova viva. */
    long countByRunIdIn(Collection<UUID> runIds);

    /** F.14 (ADR-0028): DELETE em LOTE de uma página de eventos (bulk JPQL). Devolve quantos removeu. */
    @Modifying
    @Transactional
    @Query("delete from HeartbeatRunEvent e where e.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
