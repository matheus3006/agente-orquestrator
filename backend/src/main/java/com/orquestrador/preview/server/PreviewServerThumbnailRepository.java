package com.orquestrador.preview.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries SEMPRE lideradas por company_id (deny-by-default). Append-only: so save + leituras,
 * nenhum update/delete — como os irmaos preview_artifact e preview_server_manifest. As rows FICAM
 * (o WorkspaceReaper coleta workspaces, NUNCA rows — ADR-0027 §H).
 */
public interface PreviewServerThumbnailRepository extends JpaRepository<PreviewServerThumbnail, UUID> {

    Optional<PreviewServerThumbnail> findByIdAndCompanyId(UUID id, UUID companyId);

    /** O vigente para exibir na Issue/Servidores: o ULTIMO fotografado (por-run, append-only). */
    Optional<PreviewServerThumbnail> findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(UUID companyId, UUID issueId);

    /** Projecao dinamica: as listas pedem METADADOS (o content bytea de ate 2MB NAO viaja). */
    <T> List<T> findByCompanyIdAndIssueIdOrderByCreatedAtDesc(UUID companyId, UUID issueId, Class<T> type);

    /** A guarda de idempotencia por-run do capturador (B5): ja fotografamos este run? entao pula. */
    boolean existsByCompanyIdAndRunId(UUID companyId, UUID runId);

    /** F.14 (ADR-0028): quantos thumbnails a Issue tem — o antes/depois da prova viva e do round-trip. */
    long countByCompanyIdAndIssueId(UUID companyId, UUID issueId);

    /**
     * F.14 (ADR-0028): DELETE em LOTE da cauda superseded (bulk JPQL — nunca carrega o {@code content}
     * bytea de 2MB, ao contrario de um derived delete que faria select-then-delete linha a linha).
     * Idempotente; devolve quantas linhas foram removidas.
     */
    @Modifying
    @Transactional
    @Query("delete from PreviewServerThumbnail t where t.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
