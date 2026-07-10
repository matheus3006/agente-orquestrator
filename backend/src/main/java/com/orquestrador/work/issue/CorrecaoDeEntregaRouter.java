package com.orquestrador.work.issue;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O NÚCLEO do caso B (ADR-0034): melhoria de task JÁ ENTREGUE. O dono aponta na conversa que a
 * #N ficou ruim; o Questionador emite {@code ```correcao```} com SÓ o número; e AQUI, no settle
 * do turno (mesma tx), o backend:
 *
 * <ol>
 *   <li>resolve a corrente {@code #N → assignee → time → arquiteto} DETERMINISTICAMENTE (a
 *       corrente está no banco — sem estratégia não há Planejador, sem gate não há Executor:
 *       o pedido do dono É a autorização humana, D1);</li>
 *   <li>guarda de definição: a #N precisa estar DONE (caso B é para task ENTREGUE, D7);</li>
 *   <li>cria a task corretiva ({@link IssueService#createCorretiva}): descrição = as palavras do
 *       dono copiadas BYTE A BYTE de {@code ConversationTurn.ownerMessage} (paráfrase impossível
 *       por construção, D3/D4), ligada pela {@code corrige_issue_id} (V26), assignee = o
 *       arquiteto — nasce na mesa dele;</li>
 *   <li>grava a 1ª Decision de origem {@code AGENTE} do produto (D6): texto determinístico,
 *       {@code evidencia} = o verbatim — zero palavra de LLM em registro permanente;</li>
 *   <li>confirma ao dono na conversa: turno ANSWERED visível SEM run novo (molde
 *       {@code postCutNotice} da ADR-0031, M6).</li>
 * </ol>
 *
 * <p>Corrente quebrada (elo vazio) ou #N não-DONE = PARA-E-AVISA acionável — NADA é criado (D2,
 * espelho da trava {@code TeamService.assertDispatchAllowed}). #N inexistente/emissor errado =
 * descarte fail-soft com log (padrão da casa). Linhagem (correção de correção) = SINAL sempre
 * visível, SEM trava numérica — cada volta começa com mensagem DO DONO, freio humano por
 * construção (D7). Idempotente sob reexecução do settle: o turno de protocolo carrega o id do
 * turno-gatilho — já protocolado não duplica task/Decision/confirmação.
 *
 * <p>Textos das mensagens e formato do protocolo: PROPOSTOS ao dono no PR (veto pendente, M6).
 */
@Component
public class CorrecaoDeEntregaRouter {

    private static final Logger log = LoggerFactory.getLogger(CorrecaoDeEntregaRouter.class);

    private final IssueRepository issues;
    private final IssueService issueService;
    private final AgentRepository agents;
    private final TeamRepository teams;
    private final DecisionLog decisions;
    private final ConversationTurnRepository turns;

    public CorrecaoDeEntregaRouter(IssueRepository issues, IssueService issueService,
                                   AgentRepository agents, TeamRepository teams,
                                   DecisionLog decisions, ConversationTurnRepository turns) {
        this.issues = issues;
        this.issueService = issueService;
        this.agents = agents;
        this.teams = teams;
        this.decisions = decisions;
        this.turns = turns;
    }

    /**
     * O seam da fatia: roteia a reclamação do dono (o turno-gatilho, fonte do verbatim) sobre a
     * task #{@code issueSeq}. Retorna {@code true} se a virada aconteceu (corretiva criada OU
     * aviso postado); {@code false} = descarte fail-soft (#N inexistente, gatilho já protocolado).
     */
    @Transactional
    public boolean rotear(ConversationTurn turnoDoDono, int issueSeq) {
        UUID companyId = turnoDoDono.getCompanyId();
        if (jaProtocolado(turnoDoDono)) {
            log.info("correcao: turno-gatilho {} já protocolado — settle reexecutado, nada a fazer",
                    turnoDoDono.getId());
            return false;
        }
        Issue original = issues.findByCompanyIdAndSeq(companyId, issueSeq).orElse(null);
        if (original == null) {
            log.warn("correcao: #{} não existe na company {} — bloco descartado (fail-soft)",
                    issueSeq, companyId);
            return false;
        }
        if (original.getStatus() != IssueStatus.DONE) {
            return avisa(turnoDoDono, issueSeq, "A #" + issueSeq + " ainda não está entregue"
                    + " (status atual: " + original.getStatus() + ") — o caso B é para task"
                    + " ENTREGUE que ficou ruim. Se ela travou, o caminho é o destravamento no"
                    + " board. Nada foi criado.");
        }
        // A corrente #N → assignee → time → arquiteto: cada elo vazio para-e-avisa ACIONÁVEL (D2).
        if (original.getAssigneeAgentId() == null) {
            return avisa(turnoDoDono, issueSeq, "A #" + issueSeq + " foi entregue sem responsável"
                    + " registrado — não sei a quem encaminhar a correção. Designe um assignee na #"
                    + issueSeq + " no board, ou me diga a qual time encaminhar. Nada foi criado.");
        }
        Agent entregador = agents.findById(original.getAssigneeAgentId()).orElse(null);
        if (entregador == null) {
            return avisa(turnoDoDono, issueSeq, "O responsável registrado na #" + issueSeq
                    + " não existe mais no quadro — designe um novo assignee na #" + issueSeq
                    + ", ou me diga a qual time encaminhar. Nada foi criado.");
        }
        Team time = entregador.getTeamId() == null ? null
                : teams.findByIdAndCompanyId(entregador.getTeamId(), companyId).orElse(null);
        if (time == null) {
            return avisa(turnoDoDono, issueSeq, "O responsável pela #" + issueSeq + " ("
                    + entregador.getName() + ") está fora de qualquer time — sem time não há"
                    + " arquiteto para receber a correção. Aloque " + entregador.getName()
                    + " a um time no board, ou me diga a qual time encaminhar. Nada foi criado.");
        }
        Agent arquiteto = time.getArquitetoAgentId() == null ? null
                : agents.findById(time.getArquitetoAgentId()).orElse(null);
        if (arquiteto == null) {
            return avisa(turnoDoDono, issueSeq, "A #" + issueSeq + " não tem arquiteto responsável:"
                    + " o time " + nomeDoTime(time) + " está sem arquiteto. Designe um no board,"
                    + " ou me diga a qual time encaminhar. Nada foi criado.");
        }
        return protocola(turnoDoDono, original, entregador, time, arquiteto);
    }

    /** O caminho feliz, tudo na MESMA tx: corretiva + Decision + confirmação. */
    private boolean protocola(ConversationTurn turnoDoDono, Issue original, Agent entregador,
                              Team time, Agent arquiteto) {
        String verbatim = turnoDoDono.getOwnerMessage();
        Issue corretiva = issueService.createCorretiva(turnoDoDono.getCompanyId(),
                "Correção da #" + original.getSeq() + " — " + original.getTitle(),
                verbatim, original.getId(), arquiteto.getId());
        String linhagem = sinalDeLinhagem(original);

        String racional = "Dono apontou melhoria na #" + original.getSeq() + " ("
                + original.getTitle() + ", entregue por " + entregador.getName() + ", time "
                + nomeDoTime(time) + "). Roteado ao arquiteto " + arquiteto.getName()
                + ". Task corretiva #" + corretiva.getSeq() + " criada com as palavras do dono."
                + (linhagem == null ? "" : " " + linhagem);
        decisions.record(new Decision(UUID.randomUUID(), turnoDoDono.getCompanyId(),
                "correção de entrega", DecisionTipo.OPERACIONAL, DecisionOrigem.AGENTE,
                "task corretiva #" + corretiva.getSeq() + " → corrige #" + original.getSeq(),
                racional, RacionalTipo.LOGICO, false)
                .anchoredToIssue(corretiva.getId())
                .withEvidencia(verbatim));

        postaProtocolo(turnoDoDono, original.getSeq(),
                (linhagem == null ? "" : linhagem + " ")
                        + "Correção da #" + original.getSeq() + " encaminhada — task #"
                        + corretiva.getSeq() + " criada para o arquiteto " + arquiteto.getName()
                        + " (time " + nomeDoTime(time) + ").");
        return true;
    }

    /** Corrente quebrada/não-DONE (D2/D7): o aviso acionável É a virada — nada é criado. */
    private boolean avisa(ConversationTurn turnoDoDono, int issueSeq, String aviso) {
        postaProtocolo(turnoDoDono, issueSeq, aviso);
        return true;
    }

    /**
     * A confirmação/aviso VISÍVEL sem run (M6, molde postCutNotice): turno já ANSWERED do
     * Questionador (o agente do turno-gatilho), sem wakeup. O ownerMessage carrega o id do
     * turno-gatilho — é a chave de idempotência do settle reexecutado.
     */
    private void postaProtocolo(ConversationTurn turnoDoDono, int issueSeq, String mensagem) {
        ConversationTurn protocolo = new ConversationTurn(UUID.randomUUID(),
                turnoDoDono.getConversationId(), turnoDoDono.getCompanyId(),
                turnoDoDono.getAgentId(), protocoloOwnerMessage(issueSeq, turnoDoDono.getId()));
        protocolo.answer(mensagem);
        turns.save(protocolo);
        // Sem wakeup: já ANSWERED — visível na thread, nenhum Run novo.
    }

    /**
     * Linhagem (D7): a #N apontada JÁ é uma corretiva → sinal "K-ésima correção na linhagem da
     * #raiz" na confirmação E na Decision. SEM trava numérica: recusar a K-ésima correção do dono
     * feriria a mão soberana — o freio é humano por construção (toda volta começa com mensagem
     * DO DONO). A corrente é acíclica por construção: corrige_issue_id só aponta task pré-existente.
     */
    private String sinalDeLinhagem(Issue original) {
        int correcoesNaLinhagem = 1;
        Issue raiz = original;
        while (raiz.getCorrigeIssueId() != null) {
            raiz = issues.findById(raiz.getCorrigeIssueId()).orElse(null);
            if (raiz == null) {
                return null; // elo colhido/inexistente: sem raiz não há conta — sinal se cala
            }
            correcoesNaLinhagem++;
        }
        return correcoesNaLinhagem < 2 ? null
                : "Atenção: esta é a " + correcoesNaLinhagem + "ª correção na linhagem da #"
                        + raiz.getSeq() + ".";
    }

    /** Já existe protocolo deste gatilho na conversa? (idempotência do settle reexecutado) */
    private boolean jaProtocolado(ConversationTurn turnoDoDono) {
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                        turnoDoDono.getConversationId(), turnoDoDono.getCompanyId()).stream()
                .anyMatch(turno -> isProtocoloDoGatilho(turno.getOwnerMessage(), turnoDoDono.getId()));
    }

    private static String protocoloOwnerMessage(int issueSeq, UUID gatilhoId) {
        return "Correção da #" + issueSeq + " — protocolo do caso B [gatilho " + gatilhoId + "]";
    }

    private static boolean isProtocoloDoGatilho(String ownerMessage, UUID gatilhoId) {
        return ownerMessage != null && ownerMessage.startsWith("Correção da #")
                && ownerMessage.contains("[gatilho " + gatilhoId + "]");
    }

    private static String nomeDoTime(Team time) {
        return time.getDominio() + " / " + time.getFoco();
    }
}
