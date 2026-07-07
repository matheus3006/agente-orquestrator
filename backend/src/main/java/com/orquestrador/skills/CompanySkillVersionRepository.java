package com.orquestrador.skills;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Camada 2 (biblioteca da company). Toda leitura lidera por {@code company_id} (invariante
 * deny-by-default, V1): mesmo que o {@code company_skill_id} venha de fonte não-confiável, a query
 * nunca serve a versão de outra company.
 */
public interface CompanySkillVersionRepository extends JpaRepository<CompanySkillVersion, UUID> {

    int countByCompanyIdAndCompanySkillId(UUID companyId, UUID companySkillId);

    Optional<CompanySkillVersion> findByCompanyIdAndCompanySkillIdAndContentHash(
            UUID companyId, UUID companySkillId, String contentHash);

    Optional<CompanySkillVersion> findByCompanyIdAndId(UUID companyId, UUID id);

    /** Maior número de versão já usado por este company_skill nesta company (0 se nenhum). */
    @Query("select coalesce(max(v.version), 0) from CompanySkillVersion v "
            + "where v.companyId = :companyId and v.companySkillId = :companySkillId")
    int maxVersion(@Param("companyId") UUID companyId, @Param("companySkillId") UUID companySkillId);
}
