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

    /**
     * <b>Board-only</b> (Fatia I-ter, A3). Cunhar chave de agente é ato do OPERADOR: o segredo volta
     * em claro e o {@code ActorResolutionFilter} o aceita como credencial de agente, então um
     * {@code assertMember} aqui deixava um AGENTE autenticado cunhar a chave de <b>qualquer outro
     * agente da company</b> — escalada lateral que transforma "sou o agente A" em "sou quem eu
     * quiser". Somada ao {@code /complete} sem identidade, era a outra metade do assalto descrito em
     * {@code docs/ficha-seguranca-complete.md}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse mint(@PathVariable UUID companyId, @PathVariable UUID agentId) {
        guard.assertBoardMember(companyId);
        return new ApiKeyResponse(apiKeys.mint(companyId, agentId));
    }

    /** O segredo em claro so aparece aqui, uma vez. */
    public record ApiKeyResponse(String apiKey) {
    }
}
