package com.orquestrador.preview.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries SEMPRE lideradas por company_id (deny-by-default). Append-only: so save + leituras,
 * nenhum update/delete — como o irmao preview_artifact.
 */
public interface PreviewServerManifestRepository extends JpaRepository<PreviewServerManifest, UUID> {

    /** O vigente da Issue: subir o servidor usa SEMPRE o ultimo declarado (ADR-0023 §2). */
    Optional<PreviewServerManifest> findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(UUID companyId, UUID issueId);

    List<PreviewServerManifest> findByCompanyIdAndIssueIdOrderByCreatedAtDesc(UUID companyId, UUID issueId);

    /**
     * Alimenta as CANDIDATAS da tela Servidores: o chamador colapsa por Issue ficando com a
     * PRIMEIRA (mais nova). Volume v1 e pequeno (self-hosted) — mesmo racional do badge do
     * Mission Control.
     */
    List<PreviewServerManifest> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
}
