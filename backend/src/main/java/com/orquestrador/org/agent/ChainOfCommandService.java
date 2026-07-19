package com.orquestrador.org.agent;

import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A CADEIA DE COMANDO DERIVADA (ADR-0045, D-ORG-9). O {@code reports_to_id} deixou de ser fonte:
 * agora ele é o ESPELHO da árvore de times — "uma verdade, duas leituras". Ninguém liga chefia na
 * mão (o {@code PATCH /reports-to} foi REMOVIDO nesta fatia); mudou de time = mudou de chefe num
 * ato só.
 *
 * <p>A derivação tem três regras, sendo a segunda a única exceção:
 * <ol>
 *   <li>agente comum → o GESTOR do próprio time;</li>
 *   <li><b>exceção</b>: se o agente É o gestor do time, ele SOBE — senão reportaria a si mesmo;</li>
 *   <li>time sem gestor → sobe pro pai, avô…; sem achar até a raiz → {@code null} = RAIZ (onde
 *       moram CEO+personas, os únicos sem time).</li>
 * </ol>
 *
 * <p>O anti-ciclo estrutural vive no {@code TeamService} (é ele que muda a árvore); aqui o walk só
 * se protege com visited-set — dado pré-corrompido não pode pendurar a leitura (mesma regra do
 * guard antigo da cadeia). Escrever o espelho é {@link #rederivar}/{@link #rederivarSubarvore},
 * chamados pelo {@code TeamService} em TODA mutação estrutural.
 */
@Service
public class ChainOfCommandService {

    private final AgentRepository agents;
    private final TeamRepository teams;

    public ChainOfCommandService(AgentRepository agents, TeamRepository teams) {
        this.agents = agents;
        this.teams = teams;
    }

    /** Deriva o chefe do agente a partir da árvore. {@code null} = raiz (acima dele só o CEO). */
    @Transactional(readOnly = true)
    public UUID chefeDe(Agent agente) {
        if (agente.getTeamId() == null) {
            return null; // sem time = na raiz, pendurado direto na empresa (CEO + personas)
        }
        Team time = teams.findById(agente.getTeamId()).orElse(null);
        if (time == null) {
            return null; // ponteiro órfão: honesto é devolver raiz, não explodir a leitura
        }
        if (time.temGestor() && !agente.getId().equals(time.getGestorAgentId())) {
            return time.getGestorAgentId();
        }
        // ele É o gestor deste time (ou o time não tem gestor): a chefia está ACIMA
        return primeiroGestorSubindo(time.getParentTeamId());
    }

    /** Reescreve o espelho de UM agente. Devolve o agente já salvo. */
    @Transactional
    public Agent rederivar(Agent agente) {
        agente.reassignReportsTo(chefeDe(agente));
        return agents.save(agente);
    }

    /**
     * Reescreve o espelho da SUBÁRVORE de um time (ele + descendentes) — o raio de alcance real de
     * uma mudança estrutural: trocar o gestor muda o chefe dos membros; mover o time muda o chefe
     * de quem está embaixo; e um time SEM gestor propaga a busca para os filhos.
     */
    @Transactional
    public void rederivarSubarvore(UUID teamId) {
        if (teamId == null) {
            return;
        }
        for (UUID id : subarvore(teamId)) {
            for (Agent membro : agents.findByTeamId(id)) {
                rederivar(membro);
            }
        }
    }

    /**
     * O time-alvo está no BRAÇO do time-raiz? (raiz ela mesma incluída — o braço é a subárvore
     * INTEIRA, D-ORG-3/D-DSG-2.) O irmão público do BFS privado {@link #subarvore}: é o guard do
     * dirigir-sem-gate (ADR-0047) — a gestora só dirige agente cujo time pertence à subárvore de
     * um time que ela gere. Null em qualquer ponta = fora (fail-closed, nunca explode a leitura).
     */
    @Transactional(readOnly = true)
    public boolean pertenceASubarvore(UUID raizTeamId, UUID alvoTeamId) {
        if (raizTeamId == null || alvoTeamId == null) {
            return false;
        }
        return subarvore(raizTeamId).contains(alvoTeamId);
    }

    /** Sobe a árvore a partir de {@code teamId} e devolve o primeiro gestor encontrado (ou null). */
    private UUID primeiroGestorSubindo(UUID teamId) {
        Set<UUID> visitados = new HashSet<>();
        UUID cursor = teamId;
        while (cursor != null && visitados.add(cursor)) {
            Team time = teams.findById(cursor).orElse(null);
            if (time == null) {
                return null;
            }
            if (time.temGestor()) {
                return time.getGestorAgentId();
            }
            cursor = time.getParentTeamId();
        }
        return null; // chegou na raiz (ou num ciclo pré-existente): o chefe é o topo
    }

    /** O time + todos os descendentes (BFS com visited-set: ciclo no dado não vira loop infinito). */
    private List<UUID> subarvore(UUID raizId) {
        Set<UUID> visitados = new HashSet<>();
        Deque<UUID> fila = new ArrayDeque<>();
        fila.add(raizId);
        while (!fila.isEmpty()) {
            UUID atual = fila.poll();
            if (!visitados.add(atual)) {
                continue;
            }
            teams.findByParentTeamId(atual).forEach(filho -> fila.add(filho.getId()));
        }
        return List.copyOf(visitados);
    }
}
