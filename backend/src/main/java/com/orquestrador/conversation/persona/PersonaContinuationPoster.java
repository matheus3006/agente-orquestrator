package com.orquestrador.conversation.persona;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
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
 *       {@code SpawnLineageGuard.MAX_DEPTH=5} (veto do dono pendente, como o MAX_CONSECUTIVE da
 *       ADR-0030).</li>
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
            // Reincidência PÓS-prossiga: a origem já recebeu "prossiga" e voltou a travar — sem o
            // dono no meio isso giraria para sempre. CORTA como a ADR-0030 (turno ANSWERED visível,
            // sem novo Run): o loop para de fato e o dono vê onde emperrou.
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
        Agent questionador = personaAgent(companyId, CeoPersona.QUESTIONADOR);
        if (questionador == null) {
            log.warn("persona: needs-grilling sem Questionador na company {} — descartado", companyId);
            return false;
        }
        postTurno(companyId, conversationId, questionador,
                KickbackTurn.ownerMessage(origem, lacuna), "persona-kickback");
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
     * Kickbacks seguidos SEM o dono no meio (M9): anda do fim para trás CONTANDO kickbacks e
     * PULANDO turnos internos (sentinelas de bastão/prossiga/reset); a primeira mensagem que não é
     * do fluxo — o dono de volta na conversa — ZERA a régua. O ping-pong autônomo
     * (P→Q→P→… sem resposta do dono) é exatamente o que acumula.
     */
    private static long kickbacksSeguidosNoTail(List<ConversationTurn> doConv) {
        long count = 0;
        for (int i = doConv.size() - 1; i >= 0; i--) {
            String message = doConv.get(i).getOwnerMessage();
            if (KickbackTurn.isKickback(message)) {
                count++;
            } else if (!ContinuationSentinel.isContinuation(message)) {
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
            if (!ContinuationSentinel.isContinuation(message) && !KickbackTurn.isKickback(message)) {
                return false; // o dono voltou à conversa — régua nova
            }
        }
        return false;
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
