package com.orquestrador.skills;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * Anexo de skill por-Issue via BOARD (Fatia 7a + 7c install-on-attach). Deny-by-default: toda rota chama
 * {@code assertMember}. Restrito a BOARD (agente-como-tool = fatia futura). O input é a identidade GLOBAL
 * ({@code skillId} do resultado do KnowAll): se já instalada, anexa a versão corrente; se não, instala do
 * catálogo global e anexa (fold 7a↔7c). O corpo carrega {@code agentId} porque o board não faz checkout
 * (não pode auto-designar): ele nomeia o agente que a Issue vai augmentar.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/issues/{issueId}/skills")
public class IssueSkillController {

    private final SkillSetService skillSets;
    private final IssueSkillAttachmentRepository attachments;
    private final CompanyAccessGuard guard;

    public IssueSkillController(SkillSetService skillSets, IssueSkillAttachmentRepository attachments,
                                CompanyAccessGuard guard) {
        this.skillSets = skillSets;
        this.attachments = attachments;
        this.guard = guard;
    }

    @GetMapping
    public List<AttachmentView> list(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        guard.assertMember(companyId);
        return attachments.findByCompanyIdAndIssueId(companyId, issueId).stream()
                .map(AttachmentView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentView attach(@PathVariable UUID companyId, @PathVariable UUID issueId,
                                 @RequestBody @Valid AttachRequest request) {
        guard.assertMember(companyId);
        Actor actor = requireBoard();
        // createdInRunId = null: anexo via REST não nasce de um Run (a fatia futura agente-como-tool preenche).
        IssueSkillAttachment attachment = skillSets.attachToIssue(companyId, issueId, request.agentId(),
                request.skillId(), actor.type(), actor.id(), null);
        return AttachmentView.from(attachment);
    }

    private Actor requireBoard() {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) anexa skills a uma Issue nesta versao");
        }
        return actor;
    }

    public record AttachRequest(@NotNull UUID skillId, @NotNull UUID agentId) {
    }

    public record AttachmentView(UUID id, UUID agentId, UUID companySkillVersionId, Instant createdAt) {
        static AttachmentView from(IssueSkillAttachment attachment) {
            return new AttachmentView(attachment.getId(), attachment.getAgentId(),
                    attachment.getCompanySkillVersionId(), attachment.getCreatedAt());
        }
    }
}
