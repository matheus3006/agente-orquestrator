package com.orquestrador.preview.server;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.work.issue.IssueRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serving board-only do thumbnail do preview v2 (F.13/ADR-0027 §B7). Duas leituras: as versoes da
 * Issue (METADADOS — o PNG bytea pesa ate 2MB, a tela busca por id sob demanda) e o conteudo por id.
 *
 * <p>Um PNG e INERTE (nao executa) — por isso o serving e MAIS simples que a foto v1: sai como
 * {@code image/png} + {@code nosniff}, direto num {@code <img>}, SEM sandbox, SEM iframe (o que o
 * HTML da foto v1 exige e o PNG dispensa). O {@code <img src>} nao manda header custom, entao o
 * front busca os bytes pelo MESMO fetcher autenticado (blob URL). Imutavel por id ->
 * {@code Cache-Control: immutable} (o front cacheia para sempre). As rows FICAM: e este endpoint que
 * mostra o registro SOBREVIVENTE quando o "Rodar app" ja morreu (workspace coletado, ADR-0026).
 */
@RestController
@RequestMapping("/api/companies/{companyId}")
public class PreviewServerThumbnailController {

    private final PreviewServerThumbnailRepository thumbnails;
    private final IssueRepository issues;
    private final CompanyAccessGuard guard;

    public PreviewServerThumbnailController(PreviewServerThumbnailRepository thumbnails,
                                            IssueRepository issues, CompanyAccessGuard guard) {
        this.thumbnails = thumbnails;
        this.issues = issues;
        this.guard = guard;
    }

    @GetMapping("/issues/{issueId}/preview-server-thumbnails")
    public List<VersionSummary> versions(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        guard.assertBoardMember(companyId);
        // Issue company-led -> fora da company e 404 (padrao da casa), nunca 200 [] indistinguivel
        // de "issue sem thumbnail".
        issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada nesta company"));
        return thumbnails
                .findByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId,
                        PreviewServerThumbnailMetadata.class)
                .stream()
                .map(VersionSummary::from)
                .toList();
    }

    @GetMapping("/preview-server-thumbnails/{thumbnailId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID companyId, @PathVariable UUID thumbnailId) {
        guard.assertBoardMember(companyId);
        PreviewServerThumbnail thumbnail = thumbnails.findByIdAndCompanyId(thumbnailId, companyId)
                .orElseThrow(() -> new NotFoundException("thumbnail nao encontrado: " + thumbnailId));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=31536000, immutable")
                .body(thumbnail.getContent());
    }

    /** Metadados de uma versao — tudo MENOS o content (que sai so pelo endpoint dedicado). */
    public record VersionSummary(UUID id, UUID runId, UUID agentId, String contentHash, int sizeBytes,
                                 Instant createdAt) {

        static VersionSummary from(PreviewServerThumbnailMetadata thumbnail) {
            return new VersionSummary(thumbnail.getId(), thumbnail.getRunId(), thumbnail.getAgentId(),
                    thumbnail.getContentHash(), thumbnail.getSizeBytes(), thumbnail.getCreatedAt());
        }
    }
}
