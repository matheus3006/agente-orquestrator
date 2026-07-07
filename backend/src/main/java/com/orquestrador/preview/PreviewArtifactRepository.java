package com.orquestrador.preview;

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
 * Queries SEMPRE lideradas por company_id (deny-by-default): preview de uma company nunca
 * aparece em consulta de outra. Append-only: so save + leituras, nenhum update/delete.
 */
public interface PreviewArtifactRepository extends JpaRepository<PreviewArtifact, UUID> {

    /**
     * Projecao dinamica: as listas pedem {@link PreviewArtifactMetadata} (o content de ate 2MB
     * NAO viaja); so o teste de persistencia pede a entidade inteira.
     */
    <T> List<T> findByCompanyIdAndIssueIdOrderByVersionDesc(UUID companyId, UUID issueId, Class<T> type);

    /** O alvo do dedup por content_hash: a ULTIMA versao da Issue (ADR-0021 §3). */
    Optional<PreviewArtifact> findTopByCompanyIdAndIssueIdOrderByVersionDesc(UUID companyId, UUID issueId);

    Optional<PreviewArtifact> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * Alimenta o badge do Mission Control (poll de 2s — por isso SO metadados): o chamador
     * colapsa por agente ficando com a PRIMEIRA (mais nova). Volume v1 e pequeno (self-hosted);
     * se virar fleet, este e o ponto a otimizar (query top-1-por-agente).
     */
    List<PreviewArtifactMetadata> findByCompanyIdOrderByCreatedAtDescVersionDesc(UUID companyId);

    /** F.14 (ADR-0028): quantos artifacts a Issue tem — o antes/depois da prova viva e do round-trip. */
    long countByCompanyIdAndIssueId(UUID companyId, UUID issueId);

    /**
     * F.14 (ADR-0028): DELETE em LOTE das versoes superseded nao-fixadas (bulk JPQL — nunca carrega o
     * {@code content} TEXT de 2MB). Idempotente; devolve quantas linhas foram removidas.
     */
    @Modifying
    @Transactional
    @Query("delete from PreviewArtifact a where a.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
