package com.orquestrador.conversation.persona;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.ProposalDescarteNotice;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.handoff.ContinuationSentinel;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enfileira o turno da PRÓXIMA persona (ADR-0031, M6) — o {@code postPersonaContinuationTurn}
 * espelho do {@code ContinuationTurnPoster} da ADR-0030, com persona-ALVO + bastão. Mesmo
 * transporte (turno PENDING + wakeup; sob run ativo o wakeup coalesce e a continuação nasce
 * FRESCA num run seguinte), mais duas regras próprias:
 *
 * <ul>
 *   <li><b>Idempotência:</b> já existe turno de fluxo-de-persona PENDING na conversa → não dobra
 *       (o settle re-executado pelo reaper não empilha viradas).</li>
 *   <li><b>Trava anti-loop de kickback (M9):</b> {@value #MAX_KICKBACKS_SEGUIDOS} kickbacks
 *       seguidos SEM o dono no meio → em vez de re-grelhar de novo, a ORIGEM recebe "prossiga com
 *       a melhor premissa e SINALIZE" (o dono pega no review/gate) — nunca trava. N espelha o
 *       {@code SpawnLineageGuard.MAX_DEPTH=5} (confirmado pelo dono em 2026-07-10, como o
 *       MAX_CONSECUTIVE da ADR-0030). A régua é COMPARTILHADA entre o kickback cirúrgico
 *       ({@link #postKickback}, ao Questionador) e o auto-kickback entre personas (rodada 9,
 *       {@link #postRetrabalho}, entre back-office) — {@link #postTetoDeRegua}.</li>
 * </ul>
 */
@Component
public class PersonaContinuationPoster {

    static final int MAX_KICKBACKS_SEGUIDOS = 5;

    private static final Logger log = LoggerFactory.getLogger(PersonaContinuationPoster.class);

    private final ConversationTurnRepository turns;
    private final AgentRepository agents;
    private final WakeupService wakeups;

    public PersonaContinuationPoster(ConversationTurnRepository turns, AgentRepository agents,
                                     WakeupService wakeups) {
        this.turns = turns;
        this.agents = agents;
        this.wakeups = wakeups;
    }

    /** Bastão adiante (D6): continuação da persona-alvo, 100% autônoma — nunca passa pelo dono. */
    @Transactional
    public boolean postBaton(UUID companyId, UUID conversationId, CeoPersona origem,
                             PersonaHandoffScanner.Handoff handoff) {
        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversationId, companyId);
        if (hasPendingPersonaFlow(doConv)) {
            return false;
        }
        Agent alvo = personaAgent(companyId, handoff.para());
        if (alvo == null) {
            log.warn("persona: handoff para '{}' sem agente na company {} — descartado (fail-soft)",
                    handoff.para().dbValue(), companyId);
            return false;
        }
        postTurno(companyId, conversationId, alvo,
                PersonaContinuationSentinel.bastao(origem, handoff.para(), handoff.bastao(), handoff.resumo()),
                "persona-baton");
        return true;
    }

    /**
     * Kickback cirúrgico (D7): continuação do QUESTIONADOR re-grelhar o dono — turno VISÍVEL
     * (a re-grelha é para o dono ler). No teto M9, vira "prossiga" para a própria origem.
     */
    @Transactional
    public boolean postKickback(UUID companyId, UUID conversationId, CeoPersona origem, String lacuna) {
        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversationId, companyId);
        if (hasPendingPersonaFlow(doConv)) {
            return false;
        }
        if (kickbacksSeguidosNoTail(doConv) >= MAX_KICKBACKS_SEGUIDOS) {
            return postTetoDeRegua(companyId, conversationId, doConv, origem, lacuna);
        }
        Agent questionador = personaAgent(companyId, CeoPersona.QUESTIONADOR);
        if (questionador == null) {
            log.warn("persona: needs-grilling sem Questionador na company {} — descartado", companyId);
            return false;
        }
        postTurno(companyId, conversationId, questionador,
                KickbackTurn.ownerMessage(origem, lacuna), "persona-kickback");
        return true;
    }

    /**
     * Auto-kickback entre personas (rodada 9, D-motor-nativo §2): a persona {@code origem} topou
     * um defeito no entregável que RECEBEU e devolve o bastão a quem entregou — INVISÍVEL (D4),
     * sem passar pelo dono. O destino NUNCA é um campo do bloco: é o bastão mais recente do TAIL
     * (a reincidência pós-prossiga responde à sentinela de "prossiga", não a um bastão fresco —
     * {@link #origemDoBastaoNoTail} anda pra trás através dela). Mesma régua M9 do kickback
     * cirúrgico (o ping-pong entre personas some do dono; sem a régua compartilhada giraria para
     * sempre) — o teto vira "prossiga" para a origem, igual ao needs-grilling.
     */
    @Transactional
    public boolean postRetrabalho(UUID companyId, UUID conversationId, CeoPersona origem, String lacuna) {
        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversationId, companyId);
        if (hasPendingPersonaFlow(doConv)) {
            return false;
        }
        CeoPersona destino = origemDoBastaoNoTail(doConv).orElse(null);
        if (destino == null) {
            log.warn("persona: retrabalho da conversa {} sem bastão no tail — sem origem para"
                    + " devolver (descartado, fail-soft)", conversationId);
            return false;
        }
        if (kickbacksSeguidosNoTail(doConv) >= MAX_KICKBACKS_SEGUIDOS) {
            return postTetoDeRegua(companyId, conversationId, doConv, origem, lacuna);
        }
        Agent alvo = personaAgent(companyId, destino);
        if (alvo == null) {
            log.warn("persona: retrabalho para '{}' sem agente na company {} — descartado (fail-soft)",
                    destino.dbValue(), companyId);
            return false;
        }
        postTurno(companyId, conversationId, alvo,
                PersonaContinuationSentinel.retrabalho(origem, destino, lacuna), "persona-retrabalho");
        return true;
    }

    /**
     * O TETO compartilhado da régua M9 (kickback cirúrgico E retrabalho entre personas contam
     * junto — os dois são "o sistema não resolveu sozinho e voltou"): na reincidência PÓS-prossiga
     * CORTA (espelho da ADR-0030, turno ANSWERED visível, sem novo Run); senão, a ORIGEM prossegue
     * com a melhor premissa e sinaliza.
     */
    private boolean postTetoDeRegua(UUID companyId, UUID conversationId, List<ConversationTurn> doConv,
                                    CeoPersona origem, String lacuna) {
        if (prossigaJaNoTail(doConv)) {
            postCutNotice(companyId, conversationId, origem, lacuna);
            return true;
        }
        Agent origemAgent = personaAgent(companyId, origem);
        if (origemAgent == null) {
            return false;
        }
        log.warn("persona: conversa {} bateu {} kickbacks seguidos — origem '{}' prossegue com a"
                + " melhor premissa e sinaliza (M9)", conversationId, MAX_KICKBACKS_SEGUIDOS,
                origem.dbValue());
        postTurno(companyId, conversationId, origemAgent,
                PersonaContinuationSentinel.prossiga(lacuna), "persona-prossiga");
        return true;
    }

    /**
     * O SEGUNDO gatilho do transporte (ADR-0035, D6): a recusa no gate volta à conversa de ORIGEM,
     * endereçada ao agente que PROPÔS (por id — vale para persona e CEO legado). Diferente do
     * needs-grilling, o gatilho é a MÃO DO DONO na API (não um settle reexecutável): sem guard de
     * idempotência e sem entrar na régua M9 — o dono na conversa é exatamente o que ZERA a régua.
     */
    @Transactional
    public boolean postRecusaDoGate(UUID companyId, UUID conversationId, UUID proponenteAgentId,
                                    String mensagem) {
        Agent proponente = agents.findById(proponenteAgentId)
                .filter(agent -> companyId.equals(agent.getCompanyId()))
                .orElse(null);
        if (proponente == null) {
            log.warn("recusa-gate: proponente {} não existe mais na company {} — kickback descartado"
                    + " (fail-soft)", proponenteAgentId, companyId);
            return false;
        }
        postTurno(companyId, conversationId, proponente, mensagem, "recusa-gate");
        return true;
    }

    private void postTurno(UUID companyId, UUID conversationId, Agent alvo, String ownerMessage,
                           String reasonPrefix) {
        ConversationTurn turno = turns.save(new ConversationTurn(
                UUID.randomUUID(), conversationId, companyId, alvo.getId(), ownerMessage));
        wakeups.enqueue(companyId, alvo.getId(), null, reasonPrefix + ":" + turno.getId());
    }

    private Agent personaAgent(UUID companyId, CeoPersona persona) {
        return agents.findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(
                companyId, CeoPersona.CEO_ROLE, persona.dbValue()).orElse(null);
    }

    /** Idempotência (espelho do M10 da ADR-0030): virada pendente cobre o settle re-executado. */
    private static boolean hasPendingPersonaFlow(List<ConversationTurn> doConv) {
        return doConv.stream().anyMatch(turno -> turno.getStatus() == TurnStatus.PENDING
                && PersonaContinuationSentinel.isPersonaFlow(turno.getOwnerMessage()));
    }

    /**
     * Kickbacks seguidos SEM o dono no meio (M9): anda do fim para trás CONTANDO kickbacks —
     * cirúrgico (D7, ao Questionador) E retrabalho entre personas (rodada 9, mesma régua: os dois
     * são "o sistema não resolveu sozinho e voltou") — e PULANDO turnos internos (sentinelas de
     * bastão/prossiga/reset e o aviso de descarte da ADR-0036 — plantado pelo sistema, não é o
     * dono de volta); a primeira mensagem que não é do fluxo — o dono de volta na conversa — ZERA
     * a régua. O ping-pong autônomo (P→Q→P→… ou P→E→P→… sem resposta do dono) é o que acumula.
     */
    private static long kickbacksSeguidosNoTail(List<ConversationTurn> doConv) {
        long count = 0;
        for (int i = doConv.size() - 1; i >= 0; i--) {
            String message = doConv.get(i).getOwnerMessage();
            if (KickbackTurn.isKickback(message) || PersonaContinuationSentinel.isRetrabalho(message)) {
                count++;
            } else if (donoDeVoltaNaConversa(message)) {
                break;
            }
        }
        return count;
    }

    /** Já houve um "prossiga" desde a última fala do dono? — reincidir depois dele é corte. */
    private static boolean prossigaJaNoTail(List<ConversationTurn> doConv) {
        for (int i = doConv.size() - 1; i >= 0; i--) {
            String message = doConv.get(i).getOwnerMessage();
            if (PersonaContinuationSentinel.isProssiga(message)) {
                return true;
            }
            if (donoDeVoltaNaConversa(message)) {
                return false; // o dono voltou à conversa — régua nova
            }
        }
        return false;
    }

    /**
     * O bastão mais recente do TAIL (rodada 9): anda pra trás PULANDO turnos internos do fluxo
     * (retrabalho/prossiga/kickback/aviso são o MESMO ping-pong sem o dono no meio — a
     * reincidência pós-prossiga responde à sentinela de "prossiga", não a um bastão fresco) até
     * achar o bastão que originou esta corrente — é a quem o retrabalho devolve. Sem bastão no
     * tail (turno raiz do dono, ou o dono já voltou): vazio — sem para onde devolver.
     */
    private static Optional<CeoPersona> origemDoBastaoNoTail(List<ConversationTurn> doConv) {
        for (int i = doConv.size() - 1; i >= 0; i--) {
            String message = doConv.get(i).getOwnerMessage();
            Optional<CeoPersona> origem = PersonaContinuationSentinel.origemDoBastao(message);
            if (origem.isPresent()) {
                return origem;
            }
            if (donoDeVoltaNaConversa(message)) {
                return Optional.empty(); // o dono voltou — sem bastão vigente
            }
        }
        return Optional.empty();
    }

    /** A primeira mensagem que NÃO é do fluxo interno de personas — o dono de volta na conversa. */
    private static boolean donoDeVoltaNaConversa(String message) {
        return !ContinuationSentinel.isContinuation(message) && !KickbackTurn.isKickback(message)
                && !ProposalDescarteNotice.isAviso(message);
    }

    /** O corte (espelho do aviso da ADR-0030): turno ANSWERED visível, sem wakeup — o loop PARA. */
    private void postCutNotice(UUID companyId, UUID conversationId, CeoPersona origem, String lacuna) {
        log.warn("persona: conversa {} reincidiu em kickback após o prossiga — corte com aviso ao dono",
                conversationId);
        // A voz do aviso é o balcão (Questionador); sem ele, a própria origem (que existe — acabou
        // de emitir o bloco). Sem nenhum dos dois não há como avisar: só o log acima resta.
        Agent portaVoz = personaAgent(companyId, CeoPersona.QUESTIONADOR);
        if (portaVoz == null) {
            portaVoz = personaAgent(companyId, origem);
        }
        if (portaVoz == null) {
            return;
        }
        ConversationTurn notice = new ConversationTurn(UUID.randomUUID(), conversationId, companyId,
                portaVoz.getId(), "Bastão parado — lacuna sem resposta");
        notice.answer("O " + origem.dbValue() + " travou de novo na mesma lacuna mesmo após seguir com"
                + " a melhor premissa: \"" + lacuna + "\". Preciso de você aqui — responda a lacuna na"
                + " conversa e eu retomo o fluxo.");
        turns.save(notice);
        // Sem wakeup: já ANSWERED — visível na thread, nenhum Run novo (o ping-pong morre aqui).
    }
}
