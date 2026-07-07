package com.orquestrador.skills;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, UUID> {

    Optional<SkillVersion> findBySkillIdAndContentHash(UUID skillId, String contentHash);

    /** Maior número de versão-base já usado por esta skill global (0 se nenhum). */
    @Query("select coalesce(max(v.version), 0) from SkillVersion v where v.skillId = :skillId")
    int maxVersion(@Param("skillId") UUID skillId);
}
