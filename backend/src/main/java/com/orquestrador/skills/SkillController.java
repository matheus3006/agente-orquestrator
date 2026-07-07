package com.orquestrador.skills;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Edge HTTP do catálogo de Skills. Deny-by-default: toda rota chama {@code assertMember(companyId)}
 * antes de tocar dados. Listar é permitido a qualquer membro (board/agent); IMPORTAR é curadoria do
 * operador — restrito a BOARD e auditado atomicamente (ver {@link SkillService#importFromConfiguredRoot}).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/skills")
public class SkillController {

    private final SkillService skills;
    private final CompanyAccessGuard guard;

    public SkillController(SkillService skills, CompanyAccessGuard guard) {
        this.skills = skills;
        this.guard = guard;
    }

    @GetMapping
    public List<SkillService.CatalogEntry> list(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        return skills.catalog(companyId);
    }

    /** Body opcional do import: {@code skillKeys} restringe aos diretórios nomeados; sem body = raiz inteira. */
    public record ImportSkillsRequest(List<String> skillKeys) {
    }

    @PostMapping("/import")
    public IndexResult importSkills(@PathVariable UUID companyId,
                                    @RequestBody(required = false) ImportSkillsRequest request) {
        guard.assertMember(companyId);
        Actor actor = requireBoard();
        List<String> skillKeys = request != null ? request.skillKeys() : null;
        return skills.importFromConfiguredRoot(companyId, actor.type(), actor.id(), skillKeys);
    }

    private Actor requireBoard() {
        Actor actor = guard.currentActor();
        if (actor == null || actor.type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) importa skills");
        }
        return actor;
    }
}
