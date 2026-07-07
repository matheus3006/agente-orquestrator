package com.orquestrador.org.roletemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Governança dos MOLDES (role-templates) — módulo profundo (ADR-0010) atrás de um seam pequeno. Espelha o
 * savelog do catálogo GLOBAL de Skills ({@code Skill}/{@code SkillVersion}): criar molde nasce v1; editar
 * ANEXA uma nova versão ({@code max(version)+1}) e re-aponta {@code current} — a v1 fica imutável, quem
 * pinou não é afetado. {@link #resolvePreset} devolve o SNAPSHOT que o hire copia.
 *
 * <p>No v1 os moldes são GLOBAIS ({@code company_id NULL}); criar/versionar é board-only (o gate por
 * ALCANCE vive no controller via {@code assertBoardMember} — ação que mexe no catálogo global). O preset
 * carrega o motor como TRIO (desacoplado do {@code engine_catalog}) + o skillset (refs {@code skill_key}
 * do catálogo global, guardadas como jsonb) + o {@code reportsToDefault} (texto sugerido, não agent id).
 */
@Service
public class RoleTemplateService {

    private final RoleTemplateRepository templates;
    private final RoleTemplateVersionRepository versions;
    private final AgentRepository agents;
    private final ObjectMapper objectMapper;

    public RoleTemplateService(RoleTemplateRepository templates, RoleTemplateVersionRepository versions,
                               AgentRepository agents, ObjectMapper objectMapper) {
        this.templates = templates;
        this.versions = versions;
        this.agents = agents;
        this.objectMapper = objectMapper;
    }

    /**
     * Cria um molde GLOBAL ({@code company_id=null}) já com a v1. Pré-check da unicidade da role global
     * (o índice parcial {@code where company_id is null} é Postgres-only; o pré-check fecha o caso
     * sequencial no H2 e degrada a corrida board-gated solo a um {@link ConflictException} limpo).
     */
    @Transactional
    public RoleTemplate createTemplate(TipoFuncao tipoFuncao, String roleEspecifica, Preset preset,
                                       String changeDescription, UUID actorId) {
        templates.findByCompanyIdIsNullAndRoleEspecifica(roleEspecifica).ifPresent(existing -> {
            throw new ConflictException("Ja existe um molde global para a role: " + roleEspecifica);
        });
        RoleTemplate template = templates.save(new RoleTemplate(UUID.randomUUID(), tipoFuncao, roleEspecifica, null));
        RoleTemplateVersion v1 = appendVersion(template.getId(), 1, preset, "inicial", changeDescription, actorId);
        template.promoteCurrentVersion(v1.getId());
        templates.save(template);
        return template;
    }

    /** Anexa uma nova versão (savelog) e re-aponta {@code current}. Editar ≠ mexer em quem pinou. */
    @Transactional
    public RoleTemplateVersion newVersion(UUID templateId, Preset preset, String changeType,
                                          String changeDescription, UUID actorId) {
        RoleTemplate template = templates.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Molde nao encontrado: " + templateId));
        int next = versions.maxVersion(templateId) + 1;
        RoleTemplateVersion version = appendVersion(templateId, next, preset, changeType, changeDescription, actorId);
        template.promoteCurrentVersion(version.getId());
        templates.save(template);
        return version;
    }

    /** Pina uma versão do molde no agente (re-pin explícito, reversível). Valida versão + tenant. */
    @Transactional
    public void pinForAgent(UUID companyId, UUID agentId, UUID versionId) {
        versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Versao de molde nao encontrada: " + versionId));
        Agent agent = agents.findById(agentId).filter(candidate -> companyId.equals(candidate.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado na company: " + agentId));
        agent.pinTemplateVersion(versionId);
        agents.save(agent);
    }

    /** O savelog completo do molde, mais recente primeiro — a leitura que a biblioteca (T.1-FE) consome. */
    @Transactional(readOnly = true)
    public List<RoleTemplateVersion> savelog(UUID templateId) {
        if (!templates.existsById(templateId)) {
            throw new NotFoundException("Molde nao encontrado: " + templateId);
        }
        return versions.findByRoleTemplateIdOrderByVersionDesc(templateId);
    }

    /** O skillset de uma versão como skillKeys — a mecânica do jsonb não sai deste módulo. */
    public List<String> skillKeys(RoleTemplateVersion version) {
        return parseSkillKeys(version.getSkillset());
    }

    /** O SNAPSHOT de uma versão — o que o hire copia (motor + skillset + reports_to sugerido). */
    @Transactional(readOnly = true)
    public Preset resolvePreset(UUID versionId) {
        RoleTemplateVersion version = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Versao de molde nao encontrada: " + versionId));
        return new Preset(version.getAdapterType(), version.getModel(), version.getEffort(),
                parseSkillKeys(version.getSkillset()), version.getReportsToDefault());
    }

    private RoleTemplateVersion appendVersion(UUID templateId, int version, Preset preset, String changeType,
                                              String changeDescription, UUID actorId) {
        // v1 board-direct: proposedBy=approvedBy=ator, status APPROVED (a fila de Review liga na sub-fatia
        // de governança do canvas — DRAFT/propose-then-commit reusam a Review, ADR-0012).
        return versions.save(new RoleTemplateVersion(UUID.randomUUID(), templateId, version,
                preset.adapterType(), preset.model(), preset.effort(), writeSkillKeys(preset.skillKeys()),
                preset.reportsToDefault(), changeType, changeDescription, actorId, actorId, "APPROVED"));
    }

    private String writeSkillKeys(List<String> skillKeys) {
        try {
            return objectMapper.writeValueAsString(skillKeys == null ? List.of() : skillKeys);
        } catch (Exception serializationFailed) {
            throw new IllegalArgumentException("skillset invalido", serializationFailed);
        }
    }

    private List<String> parseSkillKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception malformed) {
            return List.of(); // degrada em vez de derrubar a leitura (mesmo espírito do toJson dos skills)
        }
    }

    /**
     * O preset reutilizável de um molde: motor como TRIO + refs de skill do catálogo GLOBAL +
     * {@code reportsToDefault} (tipo/role sugerido, texto — a cadeia liga em T.2, não é agent id).
     */
    public record Preset(String adapterType, String model, String effort,
                         List<String> skillKeys, String reportsToDefault) {
    }
}
