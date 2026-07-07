package com.orquestrador.common.security;

import org.springframework.stereotype.Component;

/**
 * Estado de seguranca por-requisicao, mantido num ThreadLocal e limpo pelo
 * {@link ActorResolutionFilter} ao fim de cada request.
 *
 * <p>Alem do {@link Actor}, guarda o flag {@code companyAccessAsserted}: o contrato
 * deny-by-default exige que toda rota company-scoped chame o {@link CompanyAccessGuard}
 * antes de tocar dados. O {@code CompanyScopeInterceptor} verifica esse flag.
 */
@Component
public class ActorContext {

    private static final ThreadLocal<State> HOLDER = ThreadLocal.withInitial(State::new);

    private static final class State {
        private Actor actor;
        private boolean companyAccessAsserted;
    }

    public void setActor(Actor actor) {
        HOLDER.get().actor = actor;
    }

    public Actor getActor() {
        return HOLDER.get().actor;
    }

    public boolean hasActor() {
        return HOLDER.get().actor != null;
    }

    public void markCompanyAccessAsserted() {
        HOLDER.get().companyAccessAsserted = true;
    }

    public boolean isCompanyAccessAsserted() {
        return HOLDER.get().companyAccessAsserted;
    }

    public void clear() {
        HOLDER.remove();
    }
}
