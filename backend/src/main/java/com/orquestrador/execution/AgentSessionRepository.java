package com.orquestrador.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    Optional<AgentSession> findByAgentIdAndConversationId(UUID agentId, UUID conversationId);

    List<AgentSession> findByAgentId(UUID agentId);
}
