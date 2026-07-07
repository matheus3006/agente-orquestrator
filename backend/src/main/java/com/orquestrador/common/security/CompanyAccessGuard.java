package com.orquestrador.common.security;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.identity.company.CompanyMembershipRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Deny-by-default: toda rota company-scoped DEVE chamar {@link #assertMember(UUID)} antes de
 * tocar dados. AGENT so acessa a propria company; BOARD precisa de membership na company.
 * O sucesso marca o flag no {@link ActorContext}, verificado pelo {@code CompanyScopeInterceptor}.
 */
@Component
public class CompanyAccessGuard {

    private final ActorContext actorContext;
    private final CompanyMembershipRepository memberships;

    public CompanyAccessGuard(ActorContext actorContext, CompanyMembershipRepository memberships) {
        this.actorContext = actorContext;
        this.memberships = memberships;
    }

    public void assertMember(UUID companyId) {
        Actor actor = actorContext.getActor();
        if (actor == null) {
            throw new ForbiddenException("Requisicao sem ator autenticado");
        }
        boolean allowed = switch (actor.type()) {
            case AGENT -> companyId.equals(actor.companyId());
            case BOARD -> memberships.existsByCompanyIdAndUserId(companyId, actor.id());
            // SYSTEM (F.12/ADR-0026) e ato de pipeline interno — NUNCA uma requisicao autenticada.
            // Fail-safe deny: se um SYSTEM chegar num guard company-scoped, e bug; recusa.
            case SYSTEM -> false;
        };
        if (!allowed) {
            throw new ForbiddenException("Ator nao pode acessar a company " + companyId);
        }
        actorContext.markCompanyAccessAsserted();
    }

    /**
     * Superfície do operador (board-only): exige membership E persona BOARD — o mesmo gate da
     * fila de revisão, elevado ao guard. Agente autenticado nunca passa (StepTrace/feed são
     * stdout cru e auditoria: material do operador, não entra no contexto de agente).
     */
    public void assertBoardMember(UUID companyId) {
        assertMember(companyId);
        if (actorContext.getActor().type() != ActorType.BOARD) {
            throw new ForbiddenException("Apenas o operador (board) acessa esta superficie");
        }
    }

    public Actor currentActor() {
        return actorContext.getActor();
    }
}
