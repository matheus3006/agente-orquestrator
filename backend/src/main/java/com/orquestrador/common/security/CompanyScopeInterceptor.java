package com.orquestrador.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

/**
 * Tripwire de deny-by-default. Para rotas company-scoped ({@code /api/companies/{id}/...}),
 * se o handler respondeu 2xx sem que {@link CompanyAccessGuard#assertMember} tenha sido chamado,
 * isso e um BUG (potencial furo cross-tenant) e e logado em ERROR.
 *
 * <p>A garantia dura e o guard por-rota; este interceptor e a rede de seguranca que torna
 * "esqueci de checar acesso" visivel em dev/CI ao inves de virar um buraco silencioso.
 * Roda no {@code afterCompletion}, antes do filtro limpar o ThreadLocal.
 */
@Component
public class CompanyScopeInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CompanyScopeInterceptor.class);
    private static final Pattern SCOPED = Pattern.compile("^/api/companies/[^/]+(/.*)?$");

    private final ActorContext actorContext;

    public CompanyScopeInterceptor(ActorContext actorContext) {
        this.actorContext = actorContext;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception handlerException) {
        if (handlerException != null || response.getStatus() >= 400) {
            return;
        }
        if (!SCOPED.matcher(request.getRequestURI()).matches()) {
            return;
        }
        if (!actorContext.isCompanyAccessAsserted()) {
            log.error("DENY-BY-DEFAULT VIOLADO: {} {} respondeu {} sem chamar assertMember(companyId)",
                    request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }
}
