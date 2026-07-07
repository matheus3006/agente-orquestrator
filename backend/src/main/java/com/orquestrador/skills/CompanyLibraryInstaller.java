package com.orquestrador.skills;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mecanismo compartilhado de install: copia uma versão-base GLOBAL (Camada 1) para a biblioteca de uma
 * company (Camada 2). Dois callers reais o usam — o {@link SkillIndexer} (import do PC) e o
 * {@link SkillInstallService} (install-on-attach da 7c) — por isso é um seam de verdade, não hipotético.
 *
 * <p>Cria a {@link CompanySkill} ({@code source=CATALOG}) + a primeira {@link CompanySkillVersion} (v1)
 * copiando metadados/body redigido da {@link SkillVersion} global; o {@code content_hash} é o mesmo (a
 * cópia não altera o conteúdo). O {@code saveAndFlush} força a {@code uq_company_skill} a decidir AQUI —
 * cada caller escolhe a política de corrida (o importer é board-gated; o install-on-attach traduz p/ 409).
 */
@Component
public class CompanyLibraryInstaller {

    private final CompanySkillRepository companySkills;
    private final CompanySkillVersionRepository companySkillVersions;

    public CompanyLibraryInstaller(CompanySkillRepository companySkills,
                                   CompanySkillVersionRepository companySkillVersions) {
        this.companySkills = companySkills;
        this.companySkillVersions = companySkillVersions;
    }

    /** Instala a versão-base global na biblioteca da company e devolve a {@link CompanySkill} criada. */
    public CompanySkill installFromGlobalVersion(UUID companyId, Skill skill, SkillVersion globalVersion) {
        String contentHash = globalVersion.getContentHash();
        CompanySkill companySkill = companySkills.saveAndFlush(new CompanySkill(UUID.randomUUID(), companyId,
                skill.getId(), "CATALOG", globalVersion.getSourceRef(), contentHash, contentHash));
        companySkillVersions.save(new CompanySkillVersion(UUID.randomUUID(), companyId, companySkill.getId(),
                1, contentHash, globalVersion.getName(), globalVersion.getDescription(),
                globalVersion.getTriggers(), globalVersion.getBody(), "instalado do catálogo global",
                globalVersion.getId()));
        return companySkill;
    }
}
