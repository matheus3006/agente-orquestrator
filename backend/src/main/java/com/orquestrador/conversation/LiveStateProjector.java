package com.orquestrador.conversation;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Projeção READ-ONLY do estado vivo da empresa para o prompt do CEO (ADR-0018/A.8): destila
 * roster + issues + atividade recente em texto, juntando IDs→nomes NA HORA (o {@code detail} do
 * activity_log segue só-IDs, ADR-0009 — a junção é da projeção, nunca do log). CAPADA (top-N por
 * seção): o estado vivo é recorte, não despejo — a profundidade fica a 1 clique na UI e a
 * continuidade durável é da carta (ADR-0019), não deste recorte. O CEO lê o ESTADO, não a própria
 * memória do que fez: é assim que as mãos soberanas do operador o sincronizam (precedência,
 * ADR-0018).
 */
@Component
public class LiveStateProjector {

    static final int ISSUES_CAP = 30;
    static final int ACTIVITIES_CAP = 10;

    private static final EnumSet<RunStatus> ACTIVE_RUN_STATUSES =
            EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING);

    private final AgentRepository agents;
    private final TeamRepository teams;
    private final IssueRepository issues;
    private final HeartbeatRunRepository runs;
    private final ActivityLogRepository activities;

    public LiveStateProjector(AgentRepository agents, TeamRepository teams, IssueRepository issues,
                              HeartbeatRunRepository runs, ActivityLogRepository activities) {
        this.agents = agents;
        this.teams = teams;
        this.issues = issues;
        this.runs = runs;
        this.activities = activities;
    }

    @Transactional(readOnly = true)
    public String project(UUID companyId) {
        List<Agent> roster = agents.findByCompanyId(companyId).stream()
                .sorted(Comparator.comparing(Agent::getCreatedAt).thenComparing(Agent::getId))
                .toList();
        Map<UUID, Team> teamById = teams.findByCompanyIdOrderByDominioAscFocoAsc(companyId).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        Map<UUID, HeartbeatRun> activeRunByAgent = runs
                .findByCompanyIdAndStatusIn(companyId, ACTIVE_RUN_STATUSES).stream()
                .collect(Collectors.toMap(HeartbeatRun::getAgentId, Function.identity(),
                        (first, second) -> second.getCreatedAt().isAfter(first.getCreatedAt()) ? second : first));
        Map<UUID, String> agentNameById = roster.stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName));

        StringBuilder out = new StringBuilder("## Estado vivo da empresa\n");

        out.append("### Equipe (").append(roster.size()).append(")\n");
        for (Agent agent : roster) {
            out.append("- ").append(agent.getName()).append(" (").append(agent.getRole()).append(")");
            Team team = agent.getTeamId() != null ? teamById.get(agent.getTeamId()) : null;
            if (team != null) {
                out.append(" [time ").append(team.getDominio()).append("/").append(team.getFoco()).append("]");
            }
            HeartbeatRun active = activeRunByAgent.get(agent.getId());
            out.append(" — ").append(active == null ? "ocioso" : "run " + active.getStatus().name().toLowerCase());
            out.append("\n");
        }

        List<Issue> board = issues.findByCompanyId(companyId).stream()
                .sorted(Comparator.comparing(Issue::getCreatedAt).reversed())
                .limit(ISSUES_CAP)
                .toList();
        out.append("### Issues (").append(board.size()).append(" mais recentes)\n");
        if (board.isEmpty()) {
            out.append("- (nenhuma)\n");
        }
        // #N e a identidade humana da Issue (ADR-0033): o dono aponta "a task 12" e o CEO acha #12
        // aqui — o exemplo ensina a notacao, a secao de instrucoes do turno fica intocada (D4).
        for (Issue issue : board) {
            out.append("- #").append(issue.getSeq())
                    .append(" [").append(issue.getStatus().name()).append("] ").append(issue.getTitle());
            if (issue.getAssigneeAgentId() != null) {
                String name = agentNameById.get(issue.getAssigneeAgentId());
                out.append(" — ").append(name != null ? name : issue.getAssigneeAgentId());
            }
            out.append("\n");
        }

        List<ActivityLog> recent = activities.findByCompanyIdOrderByCreatedAtDesc(
                companyId, PageRequest.of(0, ACTIVITIES_CAP));
        out.append("### Atividade recente (").append(recent.size()).append(")\n");
        if (recent.isEmpty()) {
            out.append("- (nenhuma)\n");
        }
        for (ActivityLog entry : recent) {
            out.append("- ").append(entry.getCreatedAt()).append(" ").append(entry.getAction());
            String actorName = entry.getActorId() != null ? agentNameById.get(entry.getActorId()) : null;
            out.append(" por ").append(actorName != null ? actorName : entry.getActorType());
            out.append("\n");
        }
        return out.toString();
    }
}
