package com.orquestrador.org.roletemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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
        // Mão soberana do dono direto (ADR-0018): propõe e aprova num só ato (proposedBy==approvedBy).
        return createTemplate(tipoFuncao, roleEspecifica, preset, changeDescription, actorId, actorId);
    }

    /**
     * Propose-then-commit (R10-A, ADR-0017 emendada): o CEO PROPÔS ({@code proposedBy}) e o dono
     * APROVOU ({@code approvedBy}) — os dois campos que o hardcode {@code proposedBy=approvedBy=ator}
     * era um stand-in. A versão nasce só aqui (no APPROVE do gate), sempre APPROVED.
     */
    @Transactional
    public RoleTemplate createTemplate(TipoFuncao tipoFuncao, String roleEspecifica, Preset preset,
                                       String changeDescription, UUID proposedBy, UUID approvedBy) {
        templates.findByCompanyIdIsNullAndRoleEspecifica(roleEspecifica).ifPresent(existing -> {
            throw new ConflictException("Ja existe um molde global para a role: " + roleEspecifica);
        });
        RoleTemplate template = templates.save(new RoleTemplate(UUID.randomUUID(), tipoFuncao, roleEspecifica, null));
        RoleTemplateVersion v1 = appendVersion(template.getId(), 1, preset, "inicial",
                changeDescription, proposedBy, approvedBy);
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
        RoleTemplateVersion version = appendVersion(templateId, next, preset, changeType,
                changeDescription, actorId, actorId);
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

    /**
     * As CHECAGENS pontuais que a versão do molde declara (Uso B, docs §2/§4.b) — a resolução que o
     * briefing consome (por-papel, via pin) e que o validador da B-ii herdará. A mecânica do jsonb não
     * sai deste módulo (espelha {@link #skillKeys}). Fail-soft: versão sumida (pin obsoleto) ou sem
     * checks resolve VAZIO — nunca derruba o run (mesmo espírito do drift-badge, {@code AgentController}).
     */
    @Transactional(readOnly = true)
    public List<Check> checksForVersion(UUID versionId) {
        return versions.findById(versionId)
                .map(version -> parseChecks(version.getChecks()))
                .orElse(List.of());
    }

    private RoleTemplateVersion appendVersion(UUID templateId, int version, Preset preset, String changeType,
                                              String changeDescription, UUID proposedBy, UUID approvedBy) {
        // Propose-then-commit LIGADO (R10-A, ADR-0017 emendada): a fila é a APPROVAL, não a Review
        // (review V11 exige issue_id/run_id NOT NULL — proposta de molde não tem nenhum; G2). A versão
        // nasce sempre APPROVED (o "rascunho" vive na Approval PENDING, não numa linha DRAFT — espelha
        // HIRE_AGENT); proposedBy/approvedBy DISTINTOS quando o CEO propõe e o dono aprova.
        // checks NÃO vem do Preset (metadado de briefing por-versão, não snapshot de hire): toda versão
        // criada pelo service nasce com checklist VAZIO. As checks do papel arquiteto entram por SEED +
        // backfill da V32 (dado de sistema), nunca por createTemplate/newVersion (Uso B, docs §2/§4.b).
        return versions.save(new RoleTemplateVersion(UUID.randomUUID(), templateId, version,
                preset.adapterType(), preset.model(), preset.effort(), writeSkillKeys(preset.skillKeys()),
                preset.reportsToDefault(), changeType, changeDescription, proposedBy, approvedBy, "APPROVED",
                writeChecks(List.of())));
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

    private String writeChecks(List<Check> checks) {
        try {
            return objectMapper.writeValueAsString(checks == null ? List.of() : checks);
        } catch (Exception serializationFailed) {
            throw new IllegalArgumentException("checks invalido", serializationFailed);
        }
    }

    private List<Check> parseChecks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Check>>() {
            });
        } catch (Exception malformed) {
            return List.of(); // degrada em vez de derrubar o briefing (mesmo espírito do parseSkillKeys)
        }
    }

    /**
     * O preset reutilizável de um molde: motor como TRIO + refs de skill do catálogo GLOBAL +
     * {@code reportsToDefault} (tipo/role sugerido, texto — a cadeia liga em T.2, não é agent id).
     */
    public record Preset(String adapterType, String model, String effort,
                         List<String> skillKeys, String reportsToDefault) {
    }

    /**
     * Uma checagem pontual declarada pelo molde do papel (Uso B, docs §2/§4.b): asserção FACTUAL que dá
     * pra responder olhando o texto do plano, sem opinar. {@code id} = chave estável (o validador da B-ii
     * reporta por ele); {@code text} = a pergunta prosa-amigável que entra no briefing do arquiteto.
     */
    public record Check(String id, String text) {
    }
}
