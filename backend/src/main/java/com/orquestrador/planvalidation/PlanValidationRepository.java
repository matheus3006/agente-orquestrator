package com.orquestrador.planvalidation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanValidationRepository extends JpaRepository<StoredPlanValidation, UUID> {

    /** A âncora do "veredito mais recente da issue": acha a linha mais nova para descobrir o run. */
    Optional<StoredPlanValidation> findFirstByIssueIdOrderByCreatedAtDesc(UUID issueId);

    /** As checagens daquele run, na ordem em que foram conferidas (a ordem do molde). */
    List<StoredPlanValidation> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
