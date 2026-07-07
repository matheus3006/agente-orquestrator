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

import java.util.List;
import java.util.UUID;

/**
 * SkillSet base do Agent (ADR-0003). Deny-by-default: toda rota chama {@code assertMember}. Selecionar
 * é curadoria do operador (o dono define o SkillSet do agente) — restrito a BOARD; listar é livre a
 * qualquer membro.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/agents/{agentId}/skills")
public class AgentSkillController {

    private final SkillSetService skillSets;
    private final CompanyAccessGuard guard;

    public AgentSkillController(SkillSetService skillSets, CompanyAccessGuard guard) {
        this.skillSets = skillSets;
        this.guard = guard;
    }

    @GetMapping
    public List<SkillSetService.SelectionView> list(@PathVariable UUID companyId, @PathVariable UUID agentId) {
        guard.assertMember(companyId);
        return skillSets.listForAgent(companyId, agentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SelectedResponse select(@PathVariable UUID companyId, @PathVariable UUID agentId,
                                   @RequestBody @Valid SelectRequest request) {
        guard.assertMember(companyId);
        requireBoard();
        AgentSkillSelection selection = skillSets.select(companyId, agentId, request.companySkillId());
        return new SelectedResponse(selection.getCompanySkillId(), selection.getPinnedVersionId());
    }

    private void requireBoard() {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) define o SkillSet do agente");
        }
    }

    public record SelectRequest(@NotNull UUID companySkillId) {
    }

    public record SelectedResponse(UUID companySkillId, UUID pinnedVersionId) {
    }
}
