package com.orquestrador.context;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunContextManifestRepository extends JpaRepository<StoredContextManifest, UUID> {

    Optional<StoredContextManifest> findFirstByCompanyIdAndAgentIdOrderByCompiledAtDesc(UUID companyId,
                                                                                       UUID agentId);
}
