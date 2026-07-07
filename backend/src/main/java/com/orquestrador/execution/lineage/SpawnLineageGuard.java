package com.orquestrador.execution.lineage;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * A política do spawn_lineage (T.4): anti-ciclo (acordar ancestral da própria linhagem = recusado
 * — mata o loop A→B→A) + cap de profundidade. Duas faces, o padrão da casa (trava do arquiteto):
 * {@link #assertWakeupAllowed} recusa na porta (enqueue, 409); a face de CHECKOUT no dispatcher
 * cancela o run que nasceu ilegal por fora do enqueue.
 */
@Service
public class SpawnLineageGuard {

    /**
     * A hierarquia real tem 4 níveis (board→CEO→líder→arquiteto→executor); cadeia mais funda que
     * isso +1 de folga é fuga de orquestração, não estrutura.
     */
    static final int MAX_DEPTH = 5;

    private final HeartbeatRunRepository runs;

    public SpawnLineageGuard(HeartbeatRunRepository runs) {
        this.runs = runs;
    }

    /**
     * A linhagem do trabalho spawnado POR {@code causerAgentId} a partir do run
     * {@code causingRunId} (ex.: o run do CEO que emitiu a proposta aprovada). Esconde o lookup:
     * o caller nunca vê repositório nem descendTo. Run nulo/desaparecido = cadeia começa no
     * causador (raiz + ele).
     */
    public SpawnLineage spawnLineageOf(UUID causingRunId, UUID causerAgentId) {
        SpawnLineage causeLineage = Optional.ofNullable(causingRunId)
                .flatMap(runs::findById)
                .map(run -> SpawnLineage.decode(run.getSpawnLineage()))
                .orElse(SpawnLineage.root());
        return causeLineage.descendTo(causerAgentId);
    }

    /** Face ENQUEUE: a linhagem é a do PRÓPRIO wakeup (causador incluso no último elo). */
    public void assertWakeupAllowed(SpawnLineage lineage, UUID targetAgentId) {
        if (lineage.contains(targetAgentId)) {
            throw new ConflictException("acordar um ancestral da propria linhagem e recusado "
                    + "(spawn_lineage, ADR-0016): loop de orquestracao");
        }
        if (lineage.depth() > MAX_DEPTH) {
            throw new ConflictException("cadeia de spawn passou do cap de profundidade ("
                    + MAX_DEPTH + ") — linhagem funda demais (spawn_lineage, ADR-0016)");
        }
    }

    /**
     * Face CHECKOUT (preflight do dispatcher): um run que NASCEU ilegal — o próprio agente na
     * linhagem (loop materializado) ou cadeia funda demais — entrou por fora do enqueue
     * (ex.: promote-on-finalize) e é cancelado sem executar. Retorna o motivo do cancel.
     */
    public Optional<String> checkoutBlockReason(HeartbeatRun run) {
        SpawnLineage lineage = SpawnLineage.decode(run.getSpawnLineage());
        if (lineage.contains(run.getAgentId())) {
            return Optional.of("spawn_lineage: o agente e ancestral da propria linhagem (loop, ADR-0016)");
        }
        if (lineage.depth() > MAX_DEPTH) {
            return Optional.of("spawn_lineage: cadeia passou do cap de profundidade (" + MAX_DEPTH
                    + ", ADR-0016)");
        }
        return Optional.empty();
    }
}
