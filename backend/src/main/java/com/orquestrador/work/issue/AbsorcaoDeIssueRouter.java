package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.persona.PersonaHandoffScanner;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * O NÚCLEO da absorção de issues do board (ADR-0037): o dono cria uma issue e ela hoje nasce
 * ÓRFÃ (checkout é agent-driven; sem botão de empurrar, de propósito) — "quem deve delegar é o
 * CEO". Dois seams, o irmão INVERSO do caso B (lá conversa→board; aqui board→conversa):
 *
 * <ol>
 *   <li>{@link #aoNascerDoDono}: no POST do board (ator BOARD), MESMA tx da criação — a issue
 *       ganha a SUA conversa direcionada "#N — &lt;título&gt;" (D4, V27), um turno-GATILHO
 *       sentinela ao PLANEJADOR (invisível pelo filtro da ADR-0030) e o wakeup — espelho do
 *       {@code ConversationService.postMessage}. Sem varredura periódica (D1).</li>
 *   <li>{@link #rotear}: no settle do turno do Planejador, o bloco {@code ```absorcao```}
 *       aponta o TIME (nunca pessoa, D5); o backend resolve time→arquiteto DETERMINISTICAMENTE,
 *       seta o assignee ({@link IssueRepository#tryAbsorver} — a issue nasce na mesa do
 *       arquiteto, SEM wakeup automático dele), grava Decision origem AGENTE (D8) e confirma na
 *       thread com o racional do Planejador visível (G1/G6).</li>
 * </ol>
 *
 * <p>Company sem Planejador = ESTADO ILEGAL (D9.6 — faxina do operador, M12): o skip-com-WARN
 * abaixo é letra morta declarada, alcançável só no harness de teste.
 */
@Component
public class AbsorcaoDeIssueRouter {

    private static final Logger log = LoggerFactory.getLogger(AbsorcaoDeIssueRouter.class);

    private final IssueService issueService;
    private final IssueRepository issues;
    private final AgentRepository agents;
    private final TeamRepository teams;
    private final ConversationRepository conversations;
    private final ConversationTurnRepository turns;
    private final WakeupService wakeups;
    private final DecisionLog decisions;
    private final ActivityLogService activityLog;

    public AbsorcaoDeIssueRouter(IssueService issueService, IssueRepository issues,
                                 AgentRepository agents, TeamRepository teams,
                                 ConversationRepository conversations,
                                 ConversationTurnRepository turns, WakeupService wakeups,
                                 DecisionLog decisions, ActivityLogService activityLog) {
        this.issueService = issueService;
        this.issues = issues;
        this.agents = agents;
        this.teams = teams;
        this.conversations = conversations;
        this.turns = turns;
        this.wakeups = wakeups;
        this.decisions = decisions;
        this.activityLog = activityLog;
    }

    /**
     * O gatilho (D1): a issue do dono nasce, e na MESMA tx ganha conversa ancorada + turno
     * sentinela + wakeup do Planejador — atômicos como o {@code postMessage}. Dispara UMA vez,
     * na criação; issues do gate/corretiva/agente NÃO passam por aqui (nascem com dono, D2).
     */
    @Transactional
    public Issue aoNascerDoDono(UUID companyId, String title, String description, Integer riskLevel) {
        Issue issue = issueService.create(companyId, title, description, riskLevel);
        Agent planejador = personaAgent(companyId, CeoPersona.PLANEJADOR);
        if (planejador == null) {
            log.warn("absorcao: company {} sem Planejador — a #{} nasce órfã, triagem pulada"
                    + " (ESTADO ILEGAL, D9.6: erradicar via faxina do operador, não suportar)",
                    companyId, issue.getSeq());
            return issue;
        }
        Conversation conversa = conversaDaIssue(companyId, issue);
        issue.ancorarConversa(conversa.getId());
        issues.save(issue);
        ConversationTurn gatilho = turns.save(new ConversationTurn(UUID.randomUUID(),
                conversa.getId(), companyId, planejador.getId(), AbsorcaoSentinel.gatilho(issue)));
        wakeups.enqueue(companyId, planejador.getId(), null, "issue-absorcao:" + gatilho.getId());
        return issue;
    }

    /**
     * O seam do settle (M2, mesma tx do {@code settleAnswered}): roteia o bloco
     * {@code ```absorcao```} do Planejador. Retorna {@code true} se a virada aconteceu (atribuída
     * OU aviso postado); {@code false} = descarte fail-soft (#N inexistente, gatilho já
     * protocolado — o settle reexecutado pelo reaper não duplica nada, D9.1).
     */
    @Transactional
    public boolean rotear(ConversationTurn turnoDoPlanejador, PersonaHandoffScanner.Absorcao absorcao) {
        UUID companyId = turnoDoPlanejador.getCompanyId();
        if (jaProtocolado(turnoDoPlanejador)) {
            log.info("absorcao: turno-gatilho {} já protocolado — settle reexecutado, nada a fazer",
                    turnoDoPlanejador.getId());
            return false;
        }
        Issue issue = issues.findByCompanyIdAndSeq(companyId, absorcao.issue()).orElse(null);
        if (issue == null) {
            log.warn("absorcao: #{} não existe na company {} — bloco descartado (fail-soft)",
                    absorcao.issue(), companyId);
            return false;
        }
        // D9.3: já tem dono → aviso "nada mudei", NUNCA re-atribui (cobre settle reexecutado
        // sem protocolo E corrida com checkout — o tryAbsorver 0-linhas abaixo é o backstop).
        if (issue.getAssigneeAgentId() != null) {
            return avisa(turnoDoPlanejador, issue.getSeq(), "A #" + issue.getSeq()
                    + " já tem dona: " + nomeDoAssignee(issue) + " — nada mudei.");
        }
        Team time = teams.findByCompanyIdAndDominioAndFoco(
                companyId, absorcao.dominio(), absorcao.foco()).orElse(null);
        if (time == null) {
            // D6 (recomendação de cargo): nenhum time existente cobre — o Planejador apontou o
            // time que RECOMENDA criar; o aviso relaia o racional DELE, rotulado, e ensina o
            // próximo passo. Indistinguível por construção de um time citado errado — o texto
            // serve às duas leituras (o dono vê o nome e corrige ou manda propor).
            return avisa(turnoDoPlanejador, issue.getSeq(), "A #" + issue.getSeq()
                    + " não coube em nenhum time: o time " + absorcao.dominio() + " / "
                    + absorcao.foco() + " não existe na org. Recomendação do Planejador: "
                    + absorcao.racional() + ". Nada foi atribuído. Se quiser criar esse time,"
                    + " diga aqui (ex.: \"propõe o time\") que a proposta segue ao gate; ou crie"
                    + " o time no board.");
        }
        Agent arquiteto = time.getArquitetoAgentId() == null ? null
                : agents.findById(time.getArquitetoAgentId()).orElse(null);
        if (arquiteto == null) {
            // Moldura de anomalia (legado): time sem arquiteto é o estado que o grill do
            // Arquiteto (§G.1) vai erradicar no nascimento — aqui é cinto de segurança.
            return avisa(turnoDoPlanejador, issue.getSeq(), "A #" + issue.getSeq()
                    + " não pôde ser atribuída: o time " + nomeDoTime(time)
                    + " está sem arquiteto — estado anômalo (todo time deveria nascer com"
                    + " arquiteto). Designe um no board e me peça para retomar. Nada foi atribuído.");
        }
        if (issues.tryAbsorver(issue.getId(), companyId, arquiteto.getId()) == 0) {
            return avisa(turnoDoPlanejador, issue.getSeq(), "A #" + issue.getSeq()
                    + " mudou de estado enquanto eu triava (ganhou dono ou saiu de OPEN)"
                    + " — nada mudei.");
        }
        return protocola(turnoDoPlanejador, issue, time, arquiteto, absorcao.racional());
    }

    /** O feliz, tudo na MESMA tx: Decision AGENTE (D8) + evento só-IDs + confirmação visível. */
    private boolean protocola(ConversationTurn turnoDoPlanejador, Issue issue, Team time,
                              Agent arquiteto, String racionalDoBloco) {
        String racional = "Issue #" + issue.getSeq() + " (" + issue.getTitle() + ") criada pelo"
                + " dono no board, sem dono; o Planejador triou contra a equipe e apontou o time "
                + nomeDoTime(time) + "; o sistema resolveu o arquiteto " + arquiteto.getName()
                + " (time→arquiteto) e atribuiu a issue à mesa dele.";
        decisions.record(new Decision(UUID.randomUUID(), issue.getCompanyId(),
                "absorção de issue", DecisionTipo.OPERACIONAL, DecisionOrigem.AGENTE,
                "#" + issue.getSeq() + " absorvida → time " + nomeDoTime(time)
                        + " (arquiteto: " + arquiteto.getName() + ")",
                racional, RacionalTipo.LOGICO, false)
                .anchoredToIssue(issue.getId())
                .withEvidencia(racionalDoBloco));
        activityLog.record(issue.getCompanyId(), ActorType.AGENT, turnoDoPlanejador.getAgentId(),
                ActivityAction.ISSUE_ABSORVIDA, "issue", issue.getId(),
                Map.of("teamId", time.getId(), "arquitetoAgentId", arquiteto.getId()));
        postaProtocolo(turnoDoPlanejador, issue.getSeq(), "A #" + issue.getSeq() + " foi absorvida:"
                + " atribuída ao time " + nomeDoTime(time) + ", na mesa do arquiteto "
                + arquiteto.getName() + ". Racional do Planejador: " + racionalDoBloco
                + ". O trabalho dispara pelos caminhos normais — nenhum run foi acordado.");
        return true;
    }

    /** Guarda acionada (D6/D9): o aviso visível É a virada — nada é criado/atribuído. */
    private boolean avisa(ConversationTurn turnoDoPlanejador, int issueSeq, String aviso) {
        postaProtocolo(turnoDoPlanejador, issueSeq, aviso);
        return true;
    }

    /**
     * A confirmação/aviso VISÍVEL sem run (M6, molde {@code postaProtocolo} do caso B): turno já
     * ANSWERED do Planejador (o agente do turno-gatilho), sem wakeup. O ownerMessage carrega o id
     * do turno-gatilho — a chave de idempotência do settle reexecutado.
     */
    private void postaProtocolo(ConversationTurn turnoDoPlanejador, int issueSeq, String mensagem) {
        ConversationTurn protocolo = new ConversationTurn(UUID.randomUUID(),
                turnoDoPlanejador.getConversationId(), turnoDoPlanejador.getCompanyId(),
                turnoDoPlanejador.getAgentId(), protocoloOwnerMessage(issueSeq, turnoDoPlanejador.getId()));
        protocolo.answer(mensagem);
        turns.save(protocolo);
        // Sem wakeup: já ANSWERED — visível na thread, nenhum Run novo.
    }

    /** Já existe protocolo deste gatilho na conversa? (idempotência do settle reexecutado) */
    private boolean jaProtocolado(ConversationTurn turnoDoPlanejador) {
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                        turnoDoPlanejador.getConversationId(), turnoDoPlanejador.getCompanyId()).stream()
                .anyMatch(turno -> isProtocoloDoGatilho(turno.getOwnerMessage(),
                        turnoDoPlanejador.getId()));
    }

    private static String protocoloOwnerMessage(int issueSeq, UUID gatilhoId) {
        return "Absorção da #" + issueSeq + " — protocolo da triagem [gatilho " + gatilhoId + "]";
    }

    private static boolean isProtocoloDoGatilho(String ownerMessage, UUID gatilhoId) {
        return ownerMessage != null && ownerMessage.startsWith("Absorção da #")
                && ownerMessage.contains("[gatilho " + gatilhoId + "]");
    }

    private String nomeDoAssignee(Issue issue) {
        return agents.findById(issue.getAssigneeAgentId()).map(Agent::getName)
                .orElse("um agente que já saiu do quadro");
    }

    private static String nomeDoTime(Team time) {
        return time.getDominio() + " / " + time.getFoco();
    }

    /**
     * A retoma automática (D7/M8): o APPROVE do {@code CREATE_TEAM} acabou de criar o time que
     * faltava — se a conversa do card é a de uma issue ainda ÓRFÃ, o Planejador recebe o bastão
     * "o time existe; retome a #N" (turno sentinela + wakeup), na MESMA tx do decide. Sem issue
     * órfã ancorada (card proposto noutra conversa, ou alguém já absorveu) → dispensa com log.
     */
    @Transactional
    public void retomarAposCreateTeam(UUID companyId, UUID conversationId, Team time) {
        Issue issue = issues.findFirstByCompanyIdAndConversationId(companyId, conversationId)
                .orElse(null);
        if (issue == null || issue.getAssigneeAgentId() != null) {
            log.info("absorcao: CREATE_TEAM aprovado sem issue órfã ancorada na conversa {} —"
                    + " retoma dispensada", conversationId);
            return;
        }
        Agent planejador = personaAgent(companyId, CeoPersona.PLANEJADOR);
        if (planejador == null) {
            log.warn("absorcao: company {} sem Planejador — retoma da #{} impossível (D9.6)",
                    companyId, issue.getSeq());
            return;
        }
        ConversationTurn retoma = turns.save(new ConversationTurn(UUID.randomUUID(),
                conversationId, companyId, planejador.getId(), AbsorcaoSentinel.retoma(issue, time)));
        wakeups.enqueue(companyId, planejador.getId(), null, "absorcao-retoma:" + retoma.getId());
    }

    /** Get-or-create da conversa direcionada (D4): o assunto "#N — <título>" é a identidade. */
    private Conversation conversaDaIssue(UUID companyId, Issue issue) {
        String assunto = "#" + issue.getSeq() + " — " + issue.getTitle();
        return conversations.findByCompanyIdAndSubject(companyId, assunto)
                .orElseGet(() -> conversations.save(
                        new Conversation(UUID.randomUUID(), companyId, assunto)));
    }

    private Agent personaAgent(UUID companyId, CeoPersona persona) {
        return agents.findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(
                companyId, CeoPersona.CEO_ROLE, persona.dbValue()).orElse(null);
    }
}
