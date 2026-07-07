package com.orquestrador.org.roletemplate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoleTemplateVersionRepository extends JpaRepository<RoleTemplateVersion, UUID> {

    /** O savelog completo de um molde, mais recente primeiro — a ordem que a biblioteca exibe. */
    List<RoleTemplateVersion> findByRoleTemplateIdOrderByVersionDesc(UUID roleTemplateId);

    /**
     * Maior número de versão já usado por este molde (0 se nenhum). Espelha
     * {@link com.orquestrador.skills.SkillVersionRepository#maxVersion} — {@code max(version)+1}
     * (nunca {@code count+1}, que reciclaria número após qualquer gap).
     */
    @Query("select coalesce(max(v.version), 0) from RoleTemplateVersion v where v.roleTemplateId = :templateId")
    int maxVersion(@Param("templateId") UUID templateId);
}
