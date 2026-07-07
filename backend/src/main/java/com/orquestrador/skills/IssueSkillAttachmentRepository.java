package com.orquestrador.skills;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Anexos de skill por-Issue (Fatia 7a). Toda leitura lidera por {@code company_id} (deny-by-default,
 * ADR-0001): mesmo que {@code issueId}/{@code agentId} venham de fonte não-confiável, a query nunca
 * cruza tenants. Não há update/delete — o registro é imutável (append-only).
 */
public interface IssueSkillAttachmentRepository extends JpaRepository<IssueSkillAttachment, UUID> {

    /** Anexos vigentes de uma Issue para um agente, do mais antigo ao mais novo (o último vence na união). */
    List<IssueSkillAttachment> findByCompanyIdAndIssueIdAndAgentIdOrderByCreatedAt(
            UUID companyId, UUID issueId, UUID agentId);

    /** Já existe anexo desta mesma versão nesta Issue? (idempotência do re-anexo — casa a unique da V10.) */
    Optional<IssueSkillAttachment> findByCompanyIdAndIssueIdAndCompanySkillVersionId(
            UUID companyId, UUID issueId, UUID companySkillVersionId);

    /** Todos os anexos de uma Issue (auditoria/inspeção company-scoped). */
    List<IssueSkillAttachment> findByCompanyIdAndIssueId(UUID companyId, UUID issueId);
}
