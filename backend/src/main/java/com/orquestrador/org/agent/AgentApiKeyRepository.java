package com.orquestrador.org.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentApiKeyRepository extends JpaRepository<AgentApiKey, UUID> {

    Optional<AgentApiKey> findByKeyHash(String keyHash);
}
