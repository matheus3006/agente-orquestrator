package com.orquestrador.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Escrita do ponteiro de Session do adapter (ADR-0002, Agent+Conversation): upsert por turno de
 * conversa. WRITE-ONLY no v1 — ninguém lê ({@code --resume} desligado, ADR-0018); religar a
 * continuidade nativa é ler daqui e preencher {@code priorSession} no Context.
 */
@Service
public class AgentSessionStore {

    private final AgentSessionRepository sessions;

    public AgentSessionStore(AgentSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** Handle nulo (adapter sem sessão, ex.: process) não cria linha — só handle real é ponteiro. */
    @Transactional
    public void record(UUID companyId, UUID agentId, UUID conversationId, String adapterType,
                       String externalSessionId, UUID runId) {
        if (externalSessionId == null || externalSessionId.isBlank()) {
            return;
        }
        AgentSession session = sessions.findByAgentIdAndConversationId(agentId, conversationId)
                .orElseGet(() -> new AgentSession(UUID.randomUUID(), companyId, agentId, conversationId, adapterType));
        session.touch(externalSessionId, runId, adapterType);
        sessions.save(session);
    }

    /**
     * O handle nativo de sessão de uma conversa (ADR-0002: "religar = ler daqui") — o PRIMEIRO leitor
     * de produção. Usado SÓ pela rede resume-cirúrgico do handoff (ADR-0030/M9), escopada a
     * destilar-e-encerrar; {@code --resume} segue desligado para CONTINUAR o trabalho (D4).
     */
    @Transactional(readOnly = true)
    public Optional<String> currentHandle(UUID agentId, UUID conversationId) {
        return sessions.findByAgentIdAndConversationId(agentId, conversationId)
                .map(AgentSession::getExternalSessionId)
                .filter(handle -> handle != null && !handle.isBlank());
    }
}
