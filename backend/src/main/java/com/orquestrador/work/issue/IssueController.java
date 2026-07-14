package com.orquestrador.work.issue;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.work.review.ReviewDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@RestController
@RequestMapping("/api/companies/{companyId}/issues")
public class IssueController {

    private final IssueService issues;
    private final CompanyAccessGuard guard;
    private final AbsorcaoDeIssueRouter absorcoes;

    public IssueController(IssueService issues, CompanyAccessGuard guard,
                           AbsorcaoDeIssueRouter absorcoes) {
        this.issues = issues;
        this.guard = guard;
        this.absorcoes = absorcoes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(@PathVariable UUID companyId, @RequestBody @Valid CreateIssueRequest request) {
        guard.assertMember(companyId);
        // Absorção (ADR-0037/D1): SÓ a issue do DONO (ator BOARD) dispara a triagem do CEO —
        // conversa ancorada + gatilho ao Planejador na MESMA tx da criação. Agente criando por
        // API segue o caminho clássico (as portas do CEO nascem com dono pelo gate, D2).
        Actor actor = guard.currentActor();
        Issue issue = actor != null && actor.type() == ActorType.BOARD
                ? absorcoes.aoNascerDoDono(companyId, request.title(), request.description(),
                        request.riskLevel())
                : issues.create(companyId, request.title(), request.description(),
                        request.riskLevel());
        return respond(issue);
    }

    @GetMapping
    public List<IssueResponse> list(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        return issues.list(companyId).stream().map(this::respond).toList();
    }

    @PostMapping("/{issueId}/checkout")
    public CheckoutResponse checkout(@PathVariable UUID companyId, @PathVariable UUID issueId,
                                     @RequestBody(required = false) CheckoutRequest request) {
        guard.assertMember(companyId);
        Actor agent = requireAgent("Apenas agentes fazem checkout de issues");
        UUID runId = request != null ? request.runId() : null;
        CheckoutResult result = issues.checkout(companyId, issueId, agent.id(), runId);
        if (!result.acquired()) {
            throw new ConflictException("Issue ja esta em execucao por outro agente/run");
        }
        return new CheckoutResponse(respond(result.issue()), result.runId());
    }

    @PostMapping("/{issueId}/complete")
    public IssueResponse complete(@PathVariable UUID companyId, @PathVariable UUID issueId,
                                  @RequestBody @Valid CompleteRequest request) {
        guard.assertMember(companyId);
        requireAgent("Apenas agentes concluem issues que executam");
        if (!issues.complete(companyId, issueId, request.runId())) {
            throw new ConflictException("Issue nao esta travada por este run");
        }
        return respond(issues.get(companyId, issueId));
    }

    @PostMapping("/{issueId}/review")
    public IssueResponse review(@PathVariable UUID companyId, @PathVariable UUID issueId,
                                @RequestBody @Valid ReviewRequest request) {
        guard.assertMember(companyId);
        Actor board = requireBoard("Apenas o operador (board) decide uma revisao");
        issues.review(companyId, issueId, request.decision(), request.reason(), request.reviewId(),
                board.type(), board.id(), request.supersedesSeq());
        return respond(issues.get(companyId, issueId));
    }

    @PostMapping("/{issueId}/unblock")
    public IssueResponse unblock(@PathVariable UUID companyId, @PathVariable UUID issueId,
                                 @RequestBody @Valid UnblockRequest request) {
        guard.assertMember(companyId);
        Actor board = requireBoard("Apenas o operador (board) destrava uma Issue");
        issues.unblock(companyId, issueId, request.decision(), board.type(), board.id());
        return respond(issues.get(companyId, issueId));
    }

    /** Toda resposta de Issue resolve o selo (corrigeSeq) num lugar só — o DTO segue aditivo. */
    private IssueResponse respond(Issue issue) {
        return IssueResponse.from(issue, issues.corrigeSeqDe(issue));
    }

    private Actor requireAgent(String message) {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.AGENT) {
            throw new ForbiddenException(message);
        }
        return actor;
    }

    private Actor requireBoard(String message) {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException(message);
        }
        return actor;
    }

    // riskLevel opcional (default 1 no server); escala 1-5 do review gate (ADR-0012).
    public record CreateIssueRequest(@NotBlank String title, String description,
                                     @Min(1) @Max(5) Integer riskLevel) {
    }

    public record CheckoutRequest(UUID runId) {
    }

    public record CompleteRequest(@NotNull UUID runId) {
    }

    public record UnblockRequest(@NotNull UnblockDecision decision) {
    }

    // reason é opcional no APPROVE (C14: em silêncio commita SEM fabricar Decisão) e obrigatório
    // no REJECT (o motivo É a Decisão); vive na tabela review (nunca no activity_log — ADR-0009)
    // e é o racional da Decisão fabricada (writer D5). reviewId (opcional) ancora a decisão no
    // ciclo visto pelo revisor: ciclo aberto diferente → 409. supersedesSeq (opcional, D11) é o
    // #N citado que esta decisão supersede — o dono sela; #N fantasma → 409.
    public record ReviewRequest(@NotNull ReviewDecision decision, @Size(max = 2000) String reason,
                                UUID reviewId, Integer supersedesSeq) {
    }

    public record CheckoutResponse(IssueResponse issue, UUID runId) {
    }

    // seq = o #N humano da Issue (ADR-0033); o id UUID segue como identidade de maquina.
    // corrigeIssueId/corrigeSeq (caso B, ADR-0034): aditivos — preenchidos so na corretiva.
    // conversationId (absorção, ADR-0037/D4): a conversa DIRECIONADA da issue — o detalhe do
    // card abre a thread por ele; nulo em issue não-absorvida (gate/corretiva/legado).
    public record IssueResponse(UUID id, UUID companyId, int seq, String title, String description,
                                String status, int riskLevel, UUID assigneeAgentId,
                                UUID executionLockRunId, Instant createdAt,
                                UUID corrigeIssueId, Integer corrigeSeq, UUID conversationId) {
        static IssueResponse from(Issue issue, Integer corrigeSeq) {
            return new IssueResponse(issue.getId(), issue.getCompanyId(), issue.getSeq(), issue.getTitle(),
                    issue.getDescription(), issue.getStatus().name(), issue.getRiskLevel(),
                    issue.getAssigneeAgentId(), issue.getExecutionLockRunId(), issue.getCreatedAt(),
                    issue.getCorrigeIssueId(), corrigeSeq, issue.getConversationId());
        }
    }
}
