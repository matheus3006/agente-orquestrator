package com.orquestrador.skills;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSkillSelectionRepository extends JpaRepository<AgentSkillSelection, UUID> {

    List<AgentSkillSelection> findByCompanyIdAndAgentId(UUID companyId, UUID agentId);

    Optional<AgentSkillSelection> findByCompanyIdAndAgentIdAndCompanySkillId(
            UUID companyId, UUID agentId, UUID companySkillId);

    /** Limpa as seleções base do agente — o re-pin SUBSTITUI o skillset (R10-A/D6). */
    long deleteByCompanyIdAndAgentId(UUID companyId, UUID agentId);
}
