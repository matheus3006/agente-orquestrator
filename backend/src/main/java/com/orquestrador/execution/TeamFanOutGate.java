package com.orquestrador.execution;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * O CAP DE FAN-OUT por arquiteto (T.4; ADR-0016 rev. 2026-07-04): o time não espalha mais runs
 * concorrentes que o {@code fan_out_cap}. Duas faces — o padrão da trava do arquiteto (T.2):
 * {@link #assertFanOutAllowed} recusa na porta (chamado SÓ quando o enqueue criaria run NOVO —
 * coalescing não é fan-out novo); {@link #fanOutBlocked} é o preflight do dispatcher para o que
 * entrou por fora (conta só o EM-VOO: os QUEUED à frente serão julgados um a um no claim).
 * Agente sem time = sem cap (o freio do solto é o orçamento de orquestração, não o time).
 */
@Service
public class TeamFanOutGate {

    private static final List<RunStatus> ACTIVE_RUNS =
            List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING);
    private static final List<RunStatus> IN_FLIGHT_RUNS =
            List.of(RunStatus.RUNNING, RunStatus.RECOVERING);

    private final TeamRepository teams;
    private final HeartbeatRunRepository runs;

    public TeamFanOutGate(TeamRepository teams, HeartbeatRunRepository runs) {
        this.teams = teams;
        this.runs = runs;
    }

    /** Face ENQUEUE: criar mais um run ativo estouraria o cap do time → 409 acionável. */
    @Transactional(readOnly = true)
    public void assertFanOutAllowed(Agent agente) {
        Team time = teamOf(agente);
        if (time == null) {
            return;
        }
        long ativos = runs.countRunsOfTeam(time.getId(), ACTIVE_RUNS);
        if (ativos >= time.getFanOutCap()) {
            throw new ConflictException("fan-out do arquiteto no teto (" + time.getFanOutCap()
                    + " runs ativos no time " + time.getDominio() + "/" + time.getFoco()
                    + ", ADR-0016): aguarde um run terminar ou suba o cap");
        }
    }

    /** Face PREFLIGHT: com o em-voo do time já no cap, o run QUEUED é cancelado (entrou por fora). */
    @Transactional(readOnly = true)
    public boolean fanOutBlocked(Agent agente) {
        Team time = teamOf(agente);
        return time != null && runs.countRunsOfTeam(time.getId(), IN_FLIGHT_RUNS) >= time.getFanOutCap();
    }

    private Team teamOf(Agent agente) {
        if (agente.getTeamId() == null) {
            return null;
        }
        return teams.findById(agente.getTeamId()).orElse(null);
    }
}
