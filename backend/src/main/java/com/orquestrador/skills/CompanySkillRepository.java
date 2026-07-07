package com.orquestrador.skills;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanySkillRepository extends JpaRepository<CompanySkill, UUID> {

    Optional<CompanySkill> findByCompanyIdAndSkillId(UUID companyId, UUID skillId);

    Optional<CompanySkill> findByCompanyIdAndId(UUID companyId, UUID id);

    List<CompanySkill> findByCompanyIdOrderByCreatedAt(UUID companyId);

    /** Alguma Company já instalou esta identidade global? (descoberta ≠ instalação, ADR-0008) */
    boolean existsBySkillId(UUID skillId);

    /**
     * Das identidades globais dadas, quais JÁ estão instaladas NESTA company (Fatia 7b: anota o resultado
     * do KnowAll com estado de install). Lidera por {@code company_id} (deny-by-default): a descoberta é
     * global, mas o estado "instalada" é sempre relativo à company do chamador — nunca vaza outra company.
     */
    @Query("select companySkill.skillId from CompanySkill companySkill "
            + "where companySkill.companyId = :companyId and companySkill.skillId in :skillIds")
    List<UUID> findInstalledSkillIds(@Param("companyId") UUID companyId, @Param("skillIds") Collection<UUID> skillIds);
}
