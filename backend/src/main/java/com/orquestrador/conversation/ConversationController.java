package com.orquestrador.conversation;

import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A superfície HTTP da conversa com o CEO (T.3, ADR-0018) — BOARD-only nas duas direções: só o
 * operador vê a thread e só ele posta (a caixa de texto existe SÓ na coluna do CEO; o CEO
 * participa por dentro do motor, nunca por HTTP). POST de mensagem responde 202: o turno é
 * assíncrono (a resposta chega pelo polling da thread quando o Run liquidar).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final CompanyAccessGuard guard;

    public ConversationController(ConversationService conversations, CompanyAccessGuard guard) {
        this.conversations = conversations;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse open(@PathVariable UUID companyId, @RequestBody @Valid OpenRequest request) {
        guard.assertBoardMember(companyId);
        return ConversationResponse.from(conversations.open(companyId, request.subject().strip()));
    }

    @GetMapping
    public List<ConversationResponse> list(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        return conversations.list(companyId).stream().map(ConversationResponse::from).toList();
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TurnAcceptedResponse postMessage(@PathVariable UUID companyId, @PathVariable UUID conversationId,
                                            @RequestBody @Valid MessageRequest request) {
        guard.assertBoardMember(companyId);
        Actor actor = guard.currentActor();
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, conversationId, request.message(), actor.type(), actor.id());
        return new TurnAcceptedResponse(receipt.turnId(), receipt.runId(), receipt.runCreated());
    }

    @GetMapping("/{conversationId}/turns")
    public List<TurnResponse> thread(@PathVariable UUID companyId, @PathVariable UUID conversationId) {
        guard.assertBoardMember(companyId);
        return conversations.thread(companyId, conversationId).stream().map(TurnResponse::from).toList();
    }

    public record OpenRequest(@NotBlank @Size(max = 120) String subject) {
    }

    /** Mensagem de conversa, não documento: o cap segura payload-bomba na thread do board. */
    public record MessageRequest(@NotBlank @Size(max = 8000) String message) {
    }

    public record ConversationResponse(UUID id, String subject, Instant createdAt) {
        static ConversationResponse from(Conversation conversation) {
            return new ConversationResponse(conversation.getId(), conversation.getSubject(),
                    conversation.getCreatedAt());
        }
    }

    public record TurnAcceptedResponse(UUID turnId, UUID runId, boolean runCreated) {
    }

    public record TurnResponse(UUID id, String ownerMessage, String ceoReply, String status,
                               UUID runId, Instant createdAt, Instant answeredAt) {
        static TurnResponse from(ConversationTurn turno) {
            return new TurnResponse(turno.getId(), turno.getOwnerMessage(), turno.getCeoReply(),
                    turno.getStatus().name(), turno.getRunId(), turno.getCreatedAt(), turno.getAnsweredAt());
        }
    }
}
