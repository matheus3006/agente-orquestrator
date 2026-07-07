package com.orquestrador.common.security;

import com.orquestrador.org.agent.AgentApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Resolve o {@link Actor} da requisicao e limpa o {@link ActorContext} ao fim (ThreadLocal):
 * <ul>
 *   <li>{@code Authorization: Bearer <agent-api-key>} -> AGENT (lookup por SHA-256 do segredo)</li>
 *   <li>{@code X-Board-User: <userId>} (apenas dev) -> BOARD</li>
 * </ul>
 * O segredo nunca e logado; so o hash e comparado.
 */
@Component
public class ActorResolutionFilter extends OncePerRequestFilter {

    private final ActorContext actorContext;
    private final AgentApiKeyRepository agentApiKeys;

    public ActorResolutionFilter(ActorContext actorContext, AgentApiKeyRepository agentApiKeys) {
        this.actorContext = actorContext;
        this.agentApiKeys = agentApiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolve(request);
            chain.doFilter(request, response);
        } finally {
            actorContext.clear();
        }
    }

    private void resolve(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String hash = ApiKeyHasher.sha256Hex(auth.substring(7).trim());
            agentApiKeys.findByKeyHash(hash).ifPresent(key ->
                    actorContext.setActor(Actor.agent(key.getAgentId(), key.getCompanyId())));
            return;
        }
        String boardUser = request.getHeader("X-Board-User");
        if (boardUser != null && !boardUser.isBlank()) {
            actorContext.setActor(Actor.board(UUID.fromString(boardUser.trim())));
        }
    }
}
