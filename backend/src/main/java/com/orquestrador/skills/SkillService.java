package com.orquestrador.skills;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fachada do catálogo de Skills (camada de serviço para o {@link SkillController}).
 * Import = delega ao {@link SkillIndexer} sobre a raiz CONFIGURADA no servidor
 * ({@code orquestrador.skills.import-root}) — nunca um caminho vindo do cliente (sem path-traversal).
 *
 * <p>DECISAO v1 (deliberada): a raiz de import e UNICA (o catálogo local do operador solo, ex.
 * {@code ~/.claude/skills}); cada Company importa dela para a SUA biblioteca (install != attach).
 * As linhas persistidas continuam company-scoped. Quando houver fontes por-company, derivar a raiz
 * por companyId (ex. {@code <root>/<companyId>}) com normalize()+startsWith anti-traversal — ver ADR-0007.
 */
@Service
public class SkillService {

    private final SkillIndexer indexer;
    private final CompanySkillRepository companySkills;
    private final CompanySkillVersionRepository versions;
    private final ActivityLogService activityLog;
    private final String importRoot;

    public SkillService(SkillIndexer indexer, CompanySkillRepository companySkills,
                        CompanySkillVersionRepository versions, ActivityLogService activityLog,
                        @Value("${orquestrador.skills.import-root:${user.home}/.claude/skills}") String importRoot) {
        this.indexer = indexer;
        this.companySkills = companySkills;
        this.versions = versions;
        this.activityLog = activityLog;
        this.importRoot = importRoot;
    }

    /**
     * Importa da raiz configurada E audita, na MESMA transação: se a auditoria falhar, o import
     * reverte junto — nenhuma ação mutante fica sem trilha (ADR-0009). {@code skillKeys} restringe a
     * varredura aos diretórios nomeados sob a raiz ({@code null} = raiz inteira); a seleção pedida
     * fica registrada na trilha de auditoria.
     */
    @Transactional
    public IndexResult importFromConfiguredRoot(UUID companyId, ActorType actorType, UUID actorId,
                                                List<String> skillKeys) {
        IndexResult result = indexer.index(companyId, Path.of(importRoot), skillKeys);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("created", result.created());
        detail.put("updated", result.updated());
        detail.put("unchanged", result.unchanged());
        if (skillKeys != null) {
            detail.put("skillKeys", skillKeys);
        }
        activityLog.record(companyId, actorType, actorId, ActivityAction.SKILL_IMPORTED, "skill_catalog", null,
                detail);
        return result;
    }

    @Transactional(readOnly = true)
    public List<CatalogEntry> catalog(UUID companyId) {
        return companySkills.findByCompanyIdOrderByCreatedAt(companyId).stream()
                .map(companySkill -> {
                    CompanySkillVersion current = versions
                            .findByCompanyIdAndCompanySkillIdAndContentHash(companyId, companySkill.getId(), companySkill.getContentHash())
                            .orElse(null);
                    return new CatalogEntry(
                            companySkill.getId(),
                            current != null ? current.getName() : null,
                            companySkill.getSource(),
                            companySkill.getState(),
                            current != null ? current.getVersion() : 0,
                            current != null ? current.getDescription() : null,
                            companySkill.getUpdatedAt());
                })
                .toList();
    }

    public record CatalogEntry(UUID companySkillId, String skillKey, String source, String state,
                               int version, String description, Instant updatedAt) {
    }
}
