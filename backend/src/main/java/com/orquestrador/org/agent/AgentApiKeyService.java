package com.orquestrador.org.agent;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ApiKeyHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AgentApiKeyService {

    private final AgentRepository agents;
    private final AgentApiKeyRepository keys;
    private final SecureRandom random = new SecureRandom();

    public AgentApiKeyService(AgentRepository agents, AgentApiKeyRepository keys) {
        this.agents = agents;
        this.keys = keys;
    }

    /**
     * Cria uma chave para o agente e devolve o segredo em claro UMA UNICA VEZ.
     * Apenas o hash e persistido. O agente deve pertencer a company informada.
     */
    @Transactional
    public String mint(UUID companyId, UUID agentId) {
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado: " + agentId));
        if (!agent.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Agente nao pertence a company " + companyId);
        }
        byte[] secretBytes = new byte[24];
        random.nextBytes(secretBytes);
        String secret = "ag_" + HexFormat.of().formatHex(secretBytes);
        keys.save(new AgentApiKey(UUID.randomUUID(), companyId, agentId, ApiKeyHasher.sha256Hex(secret)));
        return secret;
    }
}
