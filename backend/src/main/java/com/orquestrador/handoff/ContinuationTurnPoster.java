package com.orquestrador.handoff;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.execution.WakeupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Enfileira o turno-CONTINUAÇÃO fresco do handoff (ADR-0030, M7) — com idempotência e a trava
 * anti-loop (M10, D7).
 *
 * <p>ZERO migração (D3): a continuação é um {@code conversation_turn} normal cujo {@code owner_message}
 * É a {@link ContinuationSentinel}. Espelha {@code ConversationService.postMessage} (cria PENDING →
 * {@code wakeups.enqueue}); o {@code claimOldestPending} a reivindica sem inspecionar o texto. Sob um
 * Run ativo do agente (o próprio turno que disparou ainda RODA) o wakeup COALESCE/DEFERE — a
 * continuação nasce FRESCA num Run seguinte (promote-on-finalize), sem religar {@code --resume} (D4).
 */
@Component
public class ContinuationTurnPoster {

    /**
     * Máx. de continuações ENCADEADAS por conversa (D7). Espelha {@code SpawnLineageGuard.MAX_DEPTH=5}
     * — o cap de profundidade de spawn; aqui rege turnos de conversa (issueId=null), que aquele guard
     * NÃO alcança. Ao bater, o CEO NÃO some: emite um turno ANSWERED visível pedindo um empurrão.
     */
    static final int MAX_CONSECUTIVE = 5;

    private static final Logger log = LoggerFactory.getLogger(ContinuationTurnPoster.class);

    private final ConversationTurnRepository turns;
    private final WakeupService wakeups;
    private final List<ConversationContinuationGuard> guards;

    public ContinuationTurnPoster(ConversationTurnRepository turns, WakeupService wakeups,
                                  List<ConversationContinuationGuard> guards) {
        this.turns = turns;
        this.wakeups = wakeups;
        this.guards = guards;
    }

    /** Cria o turno-continuação (sentinela) e acorda o agente — guardado por idempotência + trava (M10). */
    @Transactional
    public void postContinuation(UUID companyId, UUID agentId, UUID conversationId) {
        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversationId, companyId);

        // Idempotência (M10, risco #10): já existe uma continuação PENDING nesta conversa? Então este
        // disparo já está coberto — não dobra (guarda o settle reexecutado antes do claim da 1ª).
        if (hasPendingContinuation(doConv)) {
            return;
        }
        // Park-for-human (ADR-0011): a persona travou numa lacuna e ESPERA o dono — retomar por
        // contexto aqui só re-bate a MESMA lacuna (o loop kickback↔continuação, que nenhuma das duas
        // travas de contagem isoladas enxerga). O kickback ("Re-grelha", VISÍVEL) já é o aviso; uma
        // fala real do dono destrava. Sem novo turno, sem novo Run — a conversa espera.
        if (parkedForHuman(companyId, conversationId)) {
            log.debug("handoff: conversa {} parada aguardando o dono — continuação suprimida (park-for-human)",
                    conversationId);
            return;
        }
        // Trava anti-loop (D7): N continuações seguidas → corta com um turno visível, sem sumir.
        if (consecutiveContinuationsAtTail(doConv) >= MAX_CONSECUTIVE) {
            postCutNotice(companyId, agentId, conversationId);
            log.warn("handoff: conversa {} bateu o cap de {} continuações — corte com aviso ao dono",
                    conversationId, MAX_CONSECUTIVE);
            return;
        }
        ConversationTurn continuation = turns.save(new ConversationTurn(
                UUID.randomUUID(), conversationId, companyId, agentId,
                ContinuationSentinel.forConversation(conversationId)));
        wakeups.enqueue(companyId, agentId, null, "conversation-continuation:" + continuation.getId());
    }

    private static boolean hasPendingContinuation(List<ConversationTurn> doConv) {
        return doConv.stream().anyMatch(turno ->
                turno.getStatus() == TurnStatus.PENDING
                        && ContinuationSentinel.isContinuation(turno.getOwnerMessage()));
    }

    /** Algum guard pede park? Best-effort (D9): um guard que explode NÃO suprime (fail-open = retoma). */
    private boolean parkedForHuman(UUID companyId, UUID conversationId) {
        for (ConversationContinuationGuard guard : guards) {
            try {
                if (guard.parkedForHuman(companyId, conversationId)) {
                    return true;
                }
            } catch (RuntimeException exception) {
                log.warn("handoff: guard de park {} falhou (ignorado, fail-open): {}",
                        guard.getClass().getSimpleName(), exception.getMessage());
            }
        }
        return false;
    }

    /** Quantas continuações seguidas fecham a conversa AGORA (da mais nova para trás até um não-cont.). */
    private static long consecutiveContinuationsAtTail(List<ConversationTurn> doConv) {
        long count = 0;
        for (int i = doConv.size() - 1; i >= 0; i--) {
            if (!ContinuationSentinel.isContinuation(doConv.get(i).getOwnerMessage())) {
                break;
            }
            count++;
        }
        return count;
    }

    /** A trava (D7): turno ANSWERED VISÍVEL (não é sentinela) que avisa o dono do corte; sem novo Run. */
    private void postCutNotice(UUID companyId, UUID agentId, UUID conversationId) {
        ConversationTurn notice = new ConversationTurn(UUID.randomUUID(), conversationId, companyId, agentId,
                "Handoff — limite de continuações de contexto");
        notice.answer("Parei após " + MAX_CONSECUTIVE + " continuações encadeadas nesta conversa: o plano"
                + " ficou grande demais para uma sessão. Me diga o próximo foco e eu sigo daqui.");
        turns.save(notice);
        // Sem wakeup: já ANSWERED — visível na thread, sem gerar novo Run (o loop para de fato).
    }
}
