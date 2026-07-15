package com.orquestrador.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Circuit-breaker de erro repetido (ADR-0018 teto (c), R18-B). Deriva o veredito do HISTORICO ja
 * persistido — os N desfechos de execucao mais recentes do par agente×issue: se vierem todos
 * FAILED, o par esta travado. Zero estado novo (sem coluna, sem tabela; o contador NAO e o campo
 * {@code attempt}, que conta reclaims do MESMO run).
 *
 * <p>Fronteira conhecida (v1): um run que morre com lease expirado NUNCA vira FAILED — o reaper o
 * requeua RUNNING->QUEUED (ver {@link HeartbeatRunStore#reapExpired}) — entao um contador derivado
 * de FAILED nao ve morte silenciosa. Aceitavel: o breaker mira erro que SE REPETE com diagnostico
 * (o {@code last_error}), nao lease perdido.
 */
@Service
public class RepeatedFailureBreaker {

    /**
     * So desfechos de EXECUCAO entram na conta. CANCELLED (hard-stop de budget/arquiteto) e
     * INTERRUPTED (hard-stop do operador) sao abortos administrativos, nao erro do trabalho;
     * QUEUED/RUNNING/RECOVERING ainda nao desfecharam. Um SUCCEEDED recente zera a sequencia.
     */
    private static final Set<RunStatus> DESFECHOS = Set.of(RunStatus.FAILED, RunStatus.SUCCEEDED);

    private final HeartbeatRunRepository runs;
    private final int limiar;

    public RepeatedFailureBreaker(HeartbeatRunRepository runs,
            @Value("${orquestrador.circuit-breaker.consecutive-failures:3}") int limiar) {
        this.runs = runs;
        this.limiar = limiar;
    }

    /**
     * O par agente×issue esta travado? Presente = sim, com o run-gatilho (o FAILED mais recente —
     * carrega o ultimo erro e a chave de idempotencia do aviso). {@code issueId} nulo (turno de
     * conversa do CEO) nunca trava: nao ha "par agente×issue" (outro loop, fora do escopo).
     */
    @Transactional(readOnly = true)
    public Optional<Tripped> avalia(UUID agentId, UUID issueId) {
        if (issueId == null) {
            return Optional.empty();
        }
        List<HeartbeatRun> recentes = runs.findRecentOutcomesByPair(agentId, issueId, DESFECHOS,
                PageRequest.of(0, limiar));
        if (recentes.size() < limiar || !recentes.stream().allMatch(r -> r.getStatus() == RunStatus.FAILED)) {
            return Optional.empty();
        }
        HeartbeatRun gatilho = recentes.get(0);
        return Optional.of(new Tripped(gatilho.getId(), gatilho.getLastError(), limiar));
    }

    /** O par travado: o run-gatilho (FAILED mais recente), o ultimo erro e o limiar atingido. */
    public record Tripped(UUID gatilhoRunId, String ultimoErro, int falhasConsecutivas) {
    }
}
