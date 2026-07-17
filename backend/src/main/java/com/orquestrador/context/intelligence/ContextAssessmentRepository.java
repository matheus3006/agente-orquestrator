package com.orquestrador.context.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface ContextAssessmentRepository extends JpaRepository<StoredContextAssessment, UUID> {

    Optional<StoredContextAssessment> findFirstByCompanyIdAndSourceHashAndPromptVersionAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
            UUID companyId, String sourceHash, String promptVersion, String provider, String model,
            Collection<ContextAssessment.Status> statuses);
}
