package com.orquestrador.org.team;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.ChainOfCommandService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * O módulo profundo dos TIMES (ADR-0017, T.2; ampliado pela ADR-0045). Segura os invariantes de
 * composição E de ESTRUTURA — o canvas só ESPELHA o que sai daqui:
 * 1) identidade: (company, domínio, foco) é único (pré-check → 409 limpo; o unique da entidade é o backstop);
 * 2) arquiteto é MEMBRO do time — mantido POR CONSTRUÇÃO: designar valida membership, e sair do time
 *    limpa o posto (nunca ponteiro stale);
 * 3) a TRAVA DO DISPATCH: agente em time sem arquiteto não dispara trabalho (ADR-0017 — trava dura,
 *    duas faces: 409 no enqueue + preflight que cancela no dispatcher);
 * 4) <b>a ÁRVORE (ADR-0045)</b>: todo time pendura em outro time ou na RAIZ (= a empresa); sem ciclo;
 * 5) <b>gestor é MEMBRO</b> (mesmo invariante do arquiteto) e é dele que a chefia DERIVA — por isso
 *    toda mutação estrutural daqui re-deriva o espelho ({@link ChainOfCommandService}): mover
 *    agente, trocar gestor, mover time. Chefia não se edita à mão em lugar nenhum.
 * 6) <b>autoridade delegada (ADR-0046)</b>: promover só ESTAGIO→PROMOVIDO, só com gestor designado;
 *    trocar o TITULAR do posto reseta pra ESTAGIO (a confiança era naquele agente, não um selo).
 */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final AgentRepository agents;
    private final ChainOfCommandService chain;
    private final ActivityLogService activityLog;

    public TeamService(TeamRepository teams, AgentRepository agents, ChainOfCommandService chain,
                       ActivityLogService activityLog) {
        this.teams = teams;
        this.agents = agents;
        this.chain = chain;
        this.activityLog = activityLog;
    }

    /**
     * Cria o time já PENDURADO na árvore: {@code parentTeamId} nulo = filho da RAIZ (um
     * "departamento", pendurado direto na empresa). Nasce sem arquiteto e sem gestor — o time monta
     * aos poucos, e nenhum dos dois postos é condição de existir.
     */
    @Transactional
    public Team create(UUID companyId, String dominio, String foco, UUID parentTeamId) {
        String dominioLimpo = exigir(dominio, "dominio");
        String focoLimpo = exigir(foco, "foco");
        if (teams.existsByCompanyIdAndDominioAndFoco(companyId, dominioLimpo, focoLimpo)) {
            throw new ConflictException(
                    "ja existe um time " + dominioLimpo + "/" + focoLimpo + " nesta company");
        }
        if (parentTeamId != null) {
            teamDaCompany(companyId, parentTeamId); // valida existência + company antes de pendurar
        }
        return teams.save(new Team(UUID.randomUUID(), companyId, dominioLimpo, focoLimpo, parentTeamId));
    }

    @Transactional(readOnly = true)
    public List<Team> list(UUID companyId) {
        return teams.findByCompanyIdOrderByDominioAscFocoAsc(companyId);
    }

    /**
     * Move o time na árvore ({@code novoParentId} nulo = sobe pra RAIZ). Recusa fechar CICLO — ser
     * pai de si mesmo ou descer para debaixo de um descendente. A subárvore inteira re-deriva a
     * chefia: mudar o time de lugar muda o chefe de todo mundo que está embaixo dele.
     */
    @Transactional
    public Team reparentTeam(UUID companyId, UUID teamId, UUID novoParentId) {
        Team time = teamDaCompany(companyId, teamId);
        if (novoParentId != null) {
            if (novoParentId.equals(teamId)) {
                throw new ConflictException("um time nao pode ser pai de si mesmo");
            }
            Team novoPai = teamDaCompany(companyId, novoParentId);
            assertNaoFechaCiclo(time, novoPai);
        }
        time.pendurarEm(novoParentId);
        Team salvo = teams.save(time);
        chain.rederivarSubarvore(teamId);
        return salvo;
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
     * Designa o GESTOR do time ({@code agentId} nulo LIMPA o posto — a chefia dos membros volta a
     * subir pro time-pai). Membro do time, como o arquiteto (ADR-0045). Trocar o gestor re-deriva a
     * SUBÁRVORE: é o chefe de todos os membros que muda, não só o posto.
     *
     * <p><b>ADR-0046 (mini-grill Pergunta 2):</b> toda vez que o TITULAR do posto troca (agente
     * diferente do que estava, incluindo entrar/sair do posto), o marcador de autoridade delegada
     * reseta pra ESTAGIO — a confiança do dono era no julgamento daquele agente específico, não um
     * selo que sobrevive à troca. Reatribuir o MESMO agente já titular não é troca (no-op).
     */
    @Transactional
    public Team setGestor(UUID companyId, UUID teamId, UUID agentId) {
        Team team = teamDaCompany(companyId, teamId);
        if (agentId != null) {
            Agent agente = agenteDaCompany(companyId, agentId);
            if (!teamId.equals(agente.getTeamId())) {
                throw new ConflictException("o gestor tem que ser membro do time que ele gerencia"
                        + " (ADR-0045): mova o agente para o time antes");
            }
        }
        boolean trocouTitular = !Objects.equals(team.getGestorAgentId(), agentId);
        team.designarGestor(agentId);
        if (trocouTitular) {
            team.resetGestorStatus();
        }
        Team salvo = teams.save(team);
        chain.rederivarSubarvore(teamId);
        return salvo;
    }

    /**
     * Promove o gestor titular de ESTAGIO pra PROMOVIDO (ADR-0046, D-ORG-2): ato explícito e
     * board-only do dono, nunca automático. Exige gestor designado e ainda não promovido — sem
     * rebaixar (nenhuma D-ORG pede reversão). Grava o rastro do PRÓPRIO ATO de promover
     * ({@link ActivityAction#GESTOR_PROMOVIDO}); o rastro de cada issue dirigida depois é F-ORG-D.
     */
    @Transactional
    public Team promoverGestor(UUID companyId, UUID teamId, ActorType actorType, UUID actorId) {
        Team team = teamDaCompany(companyId, teamId);
        if (!team.temGestor()) {
            throw new ConflictException("o time " + team.getDominio() + "/" + team.getFoco()
                    + " nao tem gestor designado - designe um antes de promover: " + teamId);
        }
        if (team.gestorPromovido()) {
            throw new ConflictException("o gestor do time " + team.getDominio() + "/" + team.getFoco()
                    + " ja esta promovido: " + teamId);
        }
        team.promoverGestor(actorType, actorId);
        Team salvo = teams.save(team);
        activityLog.record(companyId, actorType, actorId, ActivityAction.GESTOR_PROMOVIDO,
                "team", teamId, Map.of("gestorAgentId", team.getGestorAgentId()));
        return salvo;
    }

    /**
     * Move o agente de time ({@code teamId} nulo = sai para nenhum). Se ele era ARQUITETO ou GESTOR
     * do time que está deixando, o posto é limpo na MESMA transação — os invariantes "é membro"
     * nunca ficam stale (e o dispatch do time antigo passa a travar, honesto). A chefia dele e a
     * das subárvores tocadas são re-derivadas: mudar de time É mudar de chefe (ADR-0045).
     */
    @Transactional
    public Agent assignAgent(UUID companyId, UUID agentId, UUID teamId) {
        Agent agente = agenteDaCompany(companyId, agentId);
        if (teamId != null) {
            teamDaCompany(companyId, teamId); // valida existência + company antes de apontar
        }
        UUID timeAnterior = agente.getTeamId();
        if (timeAnterior != null && !timeAnterior.equals(teamId)) {
            teams.findById(timeAnterior).ifPresent(antigo -> {
                boolean mexeu = false;
                if (agentId.equals(antigo.getArquitetoAgentId())) {
                    antigo.designarArquiteto(null);
                    mexeu = true;
                }
                if (agentId.equals(antigo.getGestorAgentId())) {
                    antigo.designarGestor(null);
                    mexeu = true;
                }
                if (mexeu) {
                    teams.save(antigo);
                }
            });
        }
        agente.assignTeam(teamId);
        Agent salvo = agents.save(agente);
        // o time que ele DEIXOU pode ter perdido o gestor (subárvore inteira re-deriva); o time em
        // que ele ENTROU não muda pros outros — só pra ele, no rederivar final.
        chain.rederivarSubarvore(timeAnterior);
        return chain.rederivar(salvo);
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

    /** Sobe a árvore a partir do novo pai: se passar pelo time, o move fecharia um ciclo. */
    private void assertNaoFechaCiclo(Team time, Team novoPai) {
        Set<UUID> visitados = new HashSet<>();
        UUID cursor = novoPai.getId();
        while (cursor != null && visitados.add(cursor)) {
            if (cursor.equals(time.getId())) {
                throw new ConflictException("mover fecharia um ciclo na arvore de times: "
                        + novoPai.getDominio() + "/" + novoPai.getFoco() + " ja esta abaixo de "
                        + time.getDominio() + "/" + time.getFoco());
            }
            cursor = teams.findById(cursor).map(Team::getParentTeamId).orElse(null);
        }
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
