package com.orquestrador.preview;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.work.issue.IssueRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serving board-only do preview (F.9/ADR-0021). Duas leituras: versoes da Issue (METADADOS — o
 * content pesa ate 2MB, a tela busca por id sob demanda) e conteudo por id. O conteudo NUNCA sai
 * como {@code text/html} navegavel: {@code text/plain; charset=utf-8} + {@code nosniff} — o iframe
 * nao manda header de auth, entao "abrir a URL numa aba" nao existe como documento; quem renderiza
 * e o PreviewFrame (srcdoc dentro de sandbox), alimentado pelo fetcher autenticado (ADR-0013).
 * Artefato imutavel por id → {@code Cache-Control: immutable} (o front cacheia para sempre).
 */
@RestController
@RequestMapping("/api/companies/{companyId}")
public class PreviewArtifactController {

    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final PreviewArtifactRepository previews;
    private final IssueRepository issues;
    private final CompanyAccessGuard guard;

    public PreviewArtifactController(PreviewArtifactRepository previews, IssueRepository issues,
                                     CompanyAccessGuard guard) {
        this.previews = previews;
        this.issues = issues;
        this.guard = guard;
    }

    @GetMapping("/issues/{issueId}/preview-artifacts")
    public List<VersionSummary> versions(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        guard.assertBoardMember(companyId);
        // Issue company-led → fora da company e 404 (padrao do IssueRunLedgerController),
        // nunca 200 [] indistinguivel de "issue sem preview".
        issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada nesta company"));
        return previews
                .findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifactMetadata.class)
                .stream()
                .map(VersionSummary::from)
                .toList();
    }

    @GetMapping("/preview-artifacts/{artifactId}/content")
    public ResponseEntity<String> content(@PathVariable UUID companyId, @PathVariable UUID artifactId) {
        guard.assertBoardMember(companyId);
        PreviewArtifact artefato = previews.findByIdAndCompanyId(artifactId, companyId)
                .orElseThrow(() -> new NotFoundException("preview artifact nao encontrado: " + artifactId));
        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF8)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=31536000, immutable")
                .body(artefato.getContent());
    }

    /** Metadados de uma versao — tudo MENOS o content (que sai so pelo endpoint dedicado). */
    public record VersionSummary(UUID id, int version, UUID runId, UUID agentId, String contentHash,
                                 int sizeBytes, Instant createdAt) {

        static VersionSummary from(PreviewArtifactMetadata artefato) {
            return new VersionSummary(artefato.getId(), artefato.getVersion(), artefato.getRunId(),
                    artefato.getAgentId(), artefato.getContentHash(), artefato.getSizeBytes(),
                    artefato.getCreatedAt());
        }
    }
}
