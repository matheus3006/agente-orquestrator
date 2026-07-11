package com.orquestrador.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndCompanyId(UUID id, UUID companyId);

    List<Conversation> findByCompanyIdOrderByCreatedAtAsc(UUID companyId);

    boolean existsByCompanyIdAndSubject(UUID companyId, String subject);

    /**
     * Resolve a conversa pelo assunto — o get-or-create da absorção (ADR-0037, M2): o assunto
     * determinístico "#N — <título>" é único por company (o unique da entidade é o backstop).
     */
    Optional<Conversation> findByCompanyIdAndSubject(UUID companyId, String subject);
}
