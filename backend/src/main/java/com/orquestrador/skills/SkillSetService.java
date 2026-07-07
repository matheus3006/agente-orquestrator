package com.orquestrador.skills;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SkillSet do agente em dois níveis (ADR-0003):
 * <ul>
 *   <li><b>base estático</b> ({@code agent_skill_selection}) — o operador seleciona skills instaladas,
 *       pinando a versão corrente; é o que {@link #resolveForAgent} devolve.</li>
 *   <li><b>aumento por-Issue</b> ({@code issue_skill_attachment}) — anexos que valem só nos Runs de
 *       uma Issue; {@link #resolveForRun} soma-os à base com precedência <b>ANEXO-VENCE-A-BASE</b>.</li>
 * </ul>
 * Tudo company-scoped (deny-by-default). O join que colapsa a união vive AQUI (não no
 * {@link SkillMaterializer}, cuja assinatura recebe o conjunto já resolvido).
 */
@Service
public class SkillSetService {

    private final AgentSkillSelectionRepository selections;
    private final IssueSkillAttachmentRepository attachments;
    private final CompanySkillRepository companySkills;
    private final CompanySkillVersionRepository versions;
    private final AgentRepository agents;
    private final IssueRepository issues;
    private final ActivityLogService activityLog;
    private final SkillInstallService installService;

    public SkillSetService(AgentSkillSelectionRepository selections, IssueSkillAttachmentRepository attachments,
                           CompanySkillRepository companySkills, CompanySkillVersionRepository versions,
                           AgentRepository agents, IssueRepository issues, ActivityLogService activityLog,
                           SkillInstallService installService) {
        this.selections = selections;
        this.attachments = attachments;
        this.companySkills = companySkills;
        this.versions = versions;
        this.agents = agents;
        this.issues = issues;
        this.activityLog = activityLog;
        this.installService = installService;
    }

    /** Seleciona uma skill instalada para o agente, pinando a versão CORRENTE. Re-seleção re-pina. */
    @Transactional
    public AgentSkillSelection select(UUID companyId, UUID agentId, UUID companySkillId) {
        Agent agent = agents.findById(agentId).filter(candidate -> companyId.equals(candidate.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado na company: " + agentId));
        CompanySkill companySkill = companySkills.findByCompanyIdAndId(companyId, companySkillId)
                .orElseThrow(() -> new NotFoundException("Skill nao instalada nesta company: " + companySkillId));
        CompanySkillVersion current = currentVersionOf(companyId, companySkill);

        AgentSkillSelection selection = selections
                .findByCompanyIdAndAgentIdAndCompanySkillId(companyId, agentId, companySkill.getId())
                .orElse(null);
        if (selection == null) {
            selection = new AgentSkillSelection(UUID.randomUUID(), companyId, agentId, companySkill.getId(), current.getId());
        } else {
            selection.repin(current.getId());
        }
        return selections.save(selection);
    }

    /**
     * Anexa a skill (identidade GLOBAL) à Issue para um agente, com INSTALL-ON-ATTACH (Fatia 7c, fold da 7a):
     * o servidor resolve o estado de install da skill — <b>INSTALLED</b> anexa a versão corrente da company
     * (1 evento {@code SKILL_ATTACHED}); <b>AVAILABLE</b> instala do catálogo global ({@code source=CATALOG},
     * 1 evento {@code SKILL_INSTALLED}) e então anexa (+1 {@code SKILL_ATTACHED}) — tudo na MESMA transação.
     * O anexo é append-only e IDEMPOTENTE (re-anexar a mesma versão devolve o registro existente). Board-only.
     */
    @Transactional
    public IssueSkillAttachment attachToIssue(UUID companyId, UUID issueId, UUID agentId, UUID globalSkillId,
                                              ActorType actorType, UUID actorId, UUID createdInRunId) {
        Issue issue = issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada na company: " + issueId));
        Agent agent = agents.findById(agentId).filter(candidate -> companyId.equals(candidate.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado na company: " + agentId));
        // Resolve install-state pela identidade GLOBAL: já na biblioteca → usa; senão → install-on-attach
        // (installFromCatalog valida a existência global: skillId inexistente vira 404 lá dentro).
        CompanySkill companySkill = companySkills.findByCompanyIdAndSkillId(companyId, globalSkillId)
                .orElseGet(() -> installService.installFromCatalog(companyId, globalSkillId, actorType, actorId));
        CompanySkillVersion current = currentVersionOf(companyId, companySkill);

        IssueSkillAttachment alreadyAttached = attachments
                .findByCompanyIdAndIssueIdAndCompanySkillVersionId(companyId, issue.getId(), current.getId())
                .orElse(null);
        if (alreadyAttached != null) {
            return alreadyAttached;
        }

        // saveAndFlush força a unique (issue_id, company_skill_version_id) a decidir AQUI. O pré-check acima
        // resolve o re-anexo SEQUENCIAL (devolve o existente); esta guarda fecha a corrida do re-anexo
        // CONCORRENTE (board-gated solo): entre o pré-check e o insert, outro anexo idêntico pode vencer —
        // a violação vira 409 limpo (sem 500, sem evento duplicado — o record só roda após o flush ok).
        IssueSkillAttachment attachment;
        try {
            attachment = attachments.saveAndFlush(new IssueSkillAttachment(UUID.randomUUID(),
                    companyId, issue.getId(), agent.getId(), current.getId(), actorId, createdInRunId));
        } catch (DataIntegrityViolationException concurrentDuplicateAttach) {
            throw new ConflictException("Skill ja anexada a esta Issue nesta versao (anexo concorrente)");
        }
        activityLog.record(companyId, actorType, actorId, ActivityAction.SKILL_ATTACHED,
                "issue_skill_attachment", attachment.getId(),
                Map.of("issueId", issue.getId(), "agentId", agent.getId(),
                        "companySkillId", companySkill.getId(), "companySkillVersionId", current.getId(),
                        "version", current.getVersion()));
        return attachment;
    }

    /** O conjunto de versões pinadas do agente — o SkillSet base materializado por padrão no Run. */
    @Transactional(readOnly = true)
    public List<CompanySkillVersion> resolveForAgent(UUID companyId, UUID agentId) {
        return selections.findByCompanyIdAndAgentId(companyId, agentId).stream()
                .map(selection -> versions.findByCompanyIdAndId(companyId, selection.getPinnedVersionId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Conjunto EFETIVO de um Run: base do agente ∪ anexos da Issue, colapsado por {@code company_skill_id}
     * com precedência <b>ANEXO-VENCE-A-BASE</b> (a versão anexada sobrescreve a do set base). Entre anexos
     * da mesma skill, o mais recente vence (ordem por {@code created_at}). Se a Issue está fechada
     * (DONE/CANCELLED), ausente, ou não há Issue (run sem tarefa), os anexos NÃO entram — o efeito efêmero
     * do anexo expira quando a Issue fecha (ADR-0003), restando só a base.
     */
    @Transactional(readOnly = true)
    public List<CompanySkillVersion> resolveForRun(UUID companyId, UUID agentId, UUID issueId) {
        List<CompanySkillVersion> baseVersions = resolveForAgent(companyId, agentId);
        if (issueId == null) {
            return baseVersions;
        }
        Issue issue = issues.findByIdAndCompanyId(issueId, companyId).orElse(null);
        if (issue == null || issueIsClosed(issue)) {
            return baseVersions;
        }
        List<CompanySkillVersion> attachedVersions = attachments
                .findByCompanyIdAndIssueIdAndAgentIdOrderByCreatedAt(companyId, issueId, agentId).stream()
                .map(attachment -> versions.findByCompanyIdAndId(companyId, attachment.getCompanySkillVersionId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        if (attachedVersions.isEmpty()) {
            return baseVersions;
        }

        // Colapsa a união por company_skill_id preservando ordem estável: base primeiro, anexos depois
        // (put sobrescreve → anexo vence a base; entre anexos, o de created_at maior — último iterado — vence).
        Map<UUID, CompanySkillVersion> effectiveVersionBySkill = new LinkedHashMap<>();
        for (CompanySkillVersion baseVersion : baseVersions) {
            effectiveVersionBySkill.put(baseVersion.getCompanySkillId(), baseVersion);
        }
        for (CompanySkillVersion attachedVersion : attachedVersions) {
            effectiveVersionBySkill.put(attachedVersion.getCompanySkillId(), attachedVersion);
        }
        return List.copyOf(effectiveVersionBySkill.values());
    }

    @Transactional(readOnly = true)
    public List<SelectionView> listForAgent(UUID companyId, UUID agentId) {
        return selections.findByCompanyIdAndAgentId(companyId, agentId).stream()
                .map(selection -> {
                    CompanySkillVersion version = versions
                            .findByCompanyIdAndId(companyId, selection.getPinnedVersionId()).orElse(null);
                    return new SelectionView(selection.getCompanySkillId(), version != null ? version.getName() : null,
                            version != null ? version.getVersion() : 0);
                })
                .toList();
    }

    private CompanySkillVersion currentVersionOf(UUID companyId, CompanySkill companySkill) {
        return versions
                .findByCompanyIdAndCompanySkillIdAndContentHash(companyId, companySkill.getId(), companySkill.getContentHash())
                .orElseThrow(() -> new NotFoundException("Versao corrente da skill nao encontrada"));
    }

    private static boolean issueIsClosed(Issue issue) {
        return issue.getStatus() == IssueStatus.DONE || issue.getStatus() == IssueStatus.CANCELLED;
    }

    public record SelectionView(UUID companySkillId, String skillKey, int version) {
    }
}
