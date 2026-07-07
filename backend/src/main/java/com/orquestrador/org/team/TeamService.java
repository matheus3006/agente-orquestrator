package com.orquestrador.org.team;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * O módulo profundo dos TIMES (ADR-0017, T.2). Segura os três invariantes de composição — o canvas
 * só ESPELHA o que sai daqui:
 * 1) identidade: (company, domínio, foco) é único (pré-check → 409 limpo; o unique da entidade é o backstop);
 * 2) arquiteto é MEMBRO do time — mantido POR CONSTRUÇÃO: designar valida membership, e sair do time
 *    limpa o posto (nunca ponteiro stale);
 * 3) a TRAVA DO DISPATCH: agente em time sem arquiteto não dispara trabalho (ADR-0017 — trava dura,
 *    duas faces: 409 no enqueue + preflight que cancela no dispatcher).
 */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final AgentRepository agents;

    public TeamService(TeamRepository teams, AgentRepository agents) {
        this.teams = teams;
        this.agents = agents;
    }

    @Transactional
    public Team create(UUID companyId, String dominio, String foco) {
        String dominioLimpo = exigir(dominio, "dominio");
        String focoLimpo = exigir(foco, "foco");
        if (teams.existsByCompanyIdAndDominioAndFoco(companyId, dominioLimpo, focoLimpo)) {
            throw new ConflictException(
                    "ja existe um time " + dominioLimpo + "/" + focoLimpo + " nesta company");
        }
        return teams.save(new Team(UUID.randomUUID(), companyId, dominioLimpo, focoLimpo));
    }

    @Transactional(readOnly = true)
    public List<Team> list(UUID companyId) {
        return teams.findByCompanyIdOrderByDominioAscFocoAsc(companyId);
    }

    /**
     * Designa o arquiteto do time ({@code agentId} nulo LIMPA o posto — o dispatch volta a travar).
     * O arquiteto tem que ser MEMBRO do time: designar alguém de fora é 409 (composição, ADR-0017).
     */
    @Transactional
    public Team setArquiteto(UUID companyId, UUID teamId, UUID agentId) {
        Team team = teamDaCompany(companyId, teamId);
        if (agentId == null) {
            team.designarArquiteto(null);
            return teams.save(team);
        }
        Agent agente = agenteDaCompany(companyId, agentId);
        if (!teamId.equals(agente.getTeamId())) {
            throw new ConflictException(
                    "o arquiteto tem que ser membro do time (ADR-0017): mova o agente para o time antes");
        }
        team.designarArquiteto(agentId);
        return teams.save(team);
    }

    /**
     * Move o agente de time ({@code teamId} nulo = sai para nenhum). Se ele era o ARQUITETO do time
     * que está deixando, o posto é limpo na MESMA transação — o invariante "arquiteto é membro"
     * nunca fica stale (e o dispatch do time antigo passa a travar, honesto).
     */
    @Transactional
    public Agent assignAgent(UUID companyId, UUID agentId, UUID teamId) {
        Agent agente = agenteDaCompany(companyId, agentId);
        if (teamId != null) {
            teamDaCompany(companyId, teamId); // valida existência + company antes de apontar
        }
        UUID timeAnterior = agente.getTeamId();
        if (timeAnterior != null && !timeAnterior.equals(teamId)) {
            teams.findById(timeAnterior)
                    .filter(antigo -> agentId.equals(antigo.getArquitetoAgentId()))
                    .ifPresent(antigo -> {
                        antigo.designarArquiteto(null);
                        teams.save(antigo);
                    });
        }
        agente.assignTeam(teamId);
        return agents.save(agente);
    }

    /** A trava do dispatch, face ENQUEUE: agente em time sem arquiteto → 409 acionável. */
    @Transactional(readOnly = true)
    public void assertDispatchAllowed(Agent agente) {
        if (dispatchBlocked(agente)) {
            throw new ConflictException("time sem arquiteto nao dispara trabalho (ADR-0017): "
                    + "designe o arquiteto do time do agente " + agente.getName() + " antes do wakeup");
        }
    }

    /** A trava do dispatch, face PREFLIGHT (dispatcher cancela o run — espelha o budget). */
    @Transactional(readOnly = true)
    public boolean dispatchBlocked(Agent agente) {
        if (agente.getTeamId() == null) {
            return false; // sem time, dispatch livre — a trava é DO TIME
        }
        return teams.findById(agente.getTeamId()).map(team -> !team.temArquiteto()).orElse(false);
    }

    private Team teamDaCompany(UUID companyId, UUID teamId) {
        return teams.findByIdAndCompanyId(teamId, companyId)
                .orElseThrow(() -> new NotFoundException("Time nao encontrado: " + teamId));
    }

    private Agent agenteDaCompany(UUID companyId, UUID agentId) {
        return agents.findById(agentId)
                .filter(candidate -> candidate.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado: " + agentId));
    }

    private static String exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " e obrigatorio");
        }
        return valor.trim();
    }
}
