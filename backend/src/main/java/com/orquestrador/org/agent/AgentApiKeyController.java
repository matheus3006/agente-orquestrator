package com.orquestrador.org.agent;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/companies/{companyId}/agents/{agentId}/api-keys")
public class AgentApiKeyController {

    private final AgentApiKeyService apiKeys;
    private final CompanyAccessGuard guard;

    public AgentApiKeyController(AgentApiKeyService apiKeys, CompanyAccessGuard guard) {
        this.apiKeys = apiKeys;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse mint(@PathVariable UUID companyId, @PathVariable UUID agentId) {
        guard.assertMember(companyId);
        return new ApiKeyResponse(apiKeys.mint(companyId, agentId));
    }

    /** O segredo em claro so aparece aqui, uma vez. */
    public record ApiKeyResponse(String apiKey) {
    }
}
