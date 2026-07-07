package com.orquestrador.skills;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Install-on-attach (Fatia 7c, ADR-0003 revisado): instala na biblioteca de uma company uma skill do
 * catálogo GLOBAL, copiando a versão-base CORRENTE ({@code skill.current_version_id}), {@code source=CATALOG}.
 * Board-only (o caller autoriza). Emite {@code SKILL_INSTALLED} na mesma transação (auditoria atômica).
 *
 * <p>Já instalada → devolve a existente sem evento (idempotente; cobre a corrida JÁ-committada). Corrida
 * SIMULTÂNEA (uq_company_skill entre o pré-check e o flush) → {@link ConflictException}/409 limpo (rollback,
 * SEM catch-and-continue numa tx já abortada no Postgres) — mesmo padrão TOCTOU da 7a, board-gated solo.
 */
@Service
public class SkillInstallService {

    private final SkillRepository skills;
    private final SkillVersionRepository skillVersions;
    private final CompanySkillRepository companySkills;
    private final CompanyLibraryInstaller libraryInstaller;
    private final ActivityLogService activityLog;

    public SkillInstallService(SkillRepository skills, SkillVersionRepository skillVersions,
                               CompanySkillRepository companySkills, CompanyLibraryInstaller libraryInstaller,
                               ActivityLogService activityLog) {
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.companySkills = companySkills;
        this.libraryInstaller = libraryInstaller;
        this.activityLog = activityLog;
    }

    /** Instala (ou devolve, se já instalada) a skill global na biblioteca da company. */
    @Transactional
    public CompanySkill installFromCatalog(UUID companyId, UUID globalSkillId, ActorType actorType, UUID actorId) {
        Skill skill = skills.findById(globalSkillId)
                .orElseThrow(() -> new NotFoundException("Skill inexistente no catalogo global: " + globalSkillId));
        CompanySkill alreadyInstalled = companySkills.findByCompanyIdAndSkillId(companyId, skill.getId()).orElse(null);
        if (alreadyInstalled != null) {
            return alreadyInstalled; // idempotente: já está na biblioteca (sem evento) — degrada p/ attach
        }
        UUID currentGlobalVersionId = skill.getCurrentVersionId();
        if (currentGlobalVersionId == null) {
            throw new ConflictException("Skill do catalogo sem versao publicada: " + globalSkillId);
        }
        SkillVersion currentGlobalVersion = skillVersions.findById(currentGlobalVersionId)
                .orElseThrow(() -> new ConflictException("Versao corrente global nao encontrada: " + currentGlobalVersionId));
        try {
            CompanySkill installed = libraryInstaller.installFromGlobalVersion(companyId, skill, currentGlobalVersion);
            activityLog.record(companyId, actorType, actorId, ActivityAction.SKILL_INSTALLED, "company_skill",
                    installed.getId(), Map.of("skillId", skill.getId(), "skillKey", skill.getSkillKey(),
                            "sourceGlobalVersionId", currentGlobalVersion.getId()));
            return installed;
        } catch (DataIntegrityViolationException concurrentInstall) {
            throw new ConflictException("Skill ja instalada nesta company (install concorrente): " + globalSkillId);
        }
    }
}
