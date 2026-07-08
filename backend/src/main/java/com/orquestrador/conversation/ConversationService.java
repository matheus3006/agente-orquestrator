package com.orquestrador.conversation;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.WakeupOutcome;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.handoff.ContinuationSentinel;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * O seam inbound da conversa (ADR-0018): a mensagem do dono vira um TURNO pendente por
 * Conversation e acorda o CEO pelo {@code WakeupService} (que já carrega a trava do arquiteto e o
 * coalescing — 1 Run ativo por Agent; turnos excedentes ficam PENDING e são atendidos um-por-Run
 * via promote-on-finalize). O board fala SÓ com o CEO: o destinatário não é escolhido, é RESOLVIDO
 * (o agente {@code role='ceo'} da company); sem CEO contratado → 409. O CEO roda sem Issue
 * (delegador puro, {@code issueId=null} — ADR-0004 emendado).
 */
@Service
public class ConversationService {

    static final String CEO_ROLE = "ceo";

    private final ConversationRepository conversations;
    private final ConversationTurnRepository turns;
    private final AgentRepository agents;
    private final WakeupService wakeups;

    public ConversationService(ConversationRepository conversations, ConversationTurnRepository turns,
                               AgentRepository agents, WakeupService wakeups) {
        this.conversations = conversations;
        this.turns = turns;
        this.agents = agents;
        this.wakeups = wakeups;
    }

    /** Abre um chat nomeado por assunto. Assunto duplicado na company → 409 (o "+" pede outro nome). */
    @Transactional
    public Conversation open(UUID companyId, String subject) {
        if (conversations.existsByCompanyIdAndSubject(companyId, subject)) {
            throw new ConflictException("Ja existe um chat com esse assunto: " + subject);
        }
        return conversations.save(new Conversation(UUID.randomUUID(), companyId, subject));
    }

    @Transactional(readOnly = true)
    public List<Conversation> list(UUID companyId) {
        return conversations.findByCompanyIdOrderByCreatedAtAsc(companyId);
    }

    @Transactional
    public TurnReceipt postMessage(UUID companyId, UUID conversationId, String message,
                                   ActorType actorType, UUID actorId) {
        Conversation conversation = conversations.findByIdAndCompanyId(conversationId, companyId)
                .orElseThrow(() -> new NotFoundException("Conversa nao encontrada: " + conversationId));
        Agent ceo = agents.findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(companyId, CEO_ROLE)
                .orElseThrow(() -> new ConflictException(
                        "A company nao tem CEO contratado (role='ceo') — o board fala so com o CEO"));

        ConversationTurn turno = turns.save(new ConversationTurn(
                UUID.randomUUID(), conversation.getId(), companyId, ceo.getId(), message));
        // O claim do turno é do MOTOR (ConversationTurnCoordinator), não do post: sob coalescing o
        // runId daqui pode ser o run RODANDO (deferred) — o followup do promote-on-finalize é quem
        // atenderá o turno. Por isso run_id nasce nulo.
        WakeupOutcome wakeup = wakeups.enqueue(companyId, ceo.getId(), null,
                "conversation-turn:" + turno.getId());
        return new TurnReceipt(turno.getId(), wakeup.runId(), wakeup.created());
    }

    @Transactional(readOnly = true)
    public List<ConversationTurn> thread(UUID companyId, UUID conversationId) {
        conversations.findByIdAndCompanyId(conversationId, companyId)
                .orElseThrow(() -> new NotFoundException("Conversa nao encontrada: " + conversationId));
        // Board INVISÍVEL na conversa (ADR-0030/D5): os turnos-continuação (sentinela) são internos —
        // o dono vê pergunta → resposta coerente; eles seguem VISÍVEIS na trilha (runs/steps). O aviso
        // de corte da trava (D7) NÃO é sentinela, então aparece normalmente.
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversationId, companyId).stream()
                .filter(turno -> !ContinuationSentinel.isContinuation(turno.getOwnerMessage()))
                .toList();
    }

    /** O recibo do post: o turno criado + o Run do CEO que vai atendê-lo (novo ou coalescido). */
    public record TurnReceipt(UUID turnId, UUID runId, boolean runCreated) {
    }
}
