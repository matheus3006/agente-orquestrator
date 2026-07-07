package com.orquestrador.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndCompanyId(UUID id, UUID companyId);

    List<Conversation> findByCompanyIdOrderByCreatedAtAsc(UUID companyId);

    boolean existsByCompanyIdAndSubject(UUID companyId, String subject);
}
