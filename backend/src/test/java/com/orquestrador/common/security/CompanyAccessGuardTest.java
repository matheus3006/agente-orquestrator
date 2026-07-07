package com.orquestrador.common.security;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.identity.company.CompanyMembershipRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrato de isolamento cross-tenant: a garantia central do deny-by-default.
 */
class CompanyAccessGuardTest {

    private final ActorContext actorContext = new ActorContext();
    private final CompanyMembershipRepository memberships = mock(CompanyMembershipRepository.class);
    private final CompanyAccessGuard guard = new CompanyAccessGuard(actorContext, memberships);

    @AfterEach
    void clear() {
        actorContext.clear();
    }

    @Test
    void agentNaoAcessaOutraCompany() {
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        actorContext.setActor(Actor.agent(UUID.randomUUID(), companyA));

        assertThatThrownBy(() -> guard.assertMember(companyB)).isInstanceOf(ForbiddenException.class);
        assertThat(actorContext.isCompanyAccessAsserted()).isFalse();
    }

    @Test
    void agentAcessaPropriaCompany() {
        UUID companyA = UUID.randomUUID();
        actorContext.setActor(Actor.agent(UUID.randomUUID(), companyA));

        guard.assertMember(companyA);

        assertThat(actorContext.isCompanyAccessAsserted()).isTrue();
    }

    @Test
    void boardPrecisaDeMembership() {
        UUID companyA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        actorContext.setActor(Actor.board(userId));

        when(memberships.existsByCompanyIdAndUserId(companyA, userId)).thenReturn(false);
        assertThatThrownBy(() -> guard.assertMember(companyA)).isInstanceOf(ForbiddenException.class);

        when(memberships.existsByCompanyIdAndUserId(companyA, userId)).thenReturn(true);
        guard.assertMember(companyA);
        assertThat(actorContext.isCompanyAccessAsserted()).isTrue();
    }

    @Test
    void semAtorEhProibido() {
        assertThatThrownBy(() -> guard.assertMember(UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
    }
}
