package com.orquestrador.governance.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByIdAndCompanyId(UUID id, UUID companyId);

    List<Approval> findByCompanyIdAndStatusOrderByRequestedAt(UUID companyId, ApprovalStatus status);

    List<Approval> findByCompanyIdAndConversationIdOrderByRequestedAt(UUID companyId, UUID conversationId);

    /** O dedupe que o índice parcial (V17) força no Postgres; no H2 é este exists que segura. */
    Optional<Approval> findFirstByCompanyIdAndRequestedByAgentIdAndActionTypeAndTargetFingerprintAndStatus(
            UUID companyId, UUID requestedByAgentId, ApprovalActionType actionType,
            String targetFingerprint, ApprovalStatus status);
}
