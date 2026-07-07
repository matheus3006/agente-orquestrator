package com.orquestrador.preview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F.9 (ADR-0021): preview_artifact e o savelog IMUTAVEL do preview — nasce por-Run, apresenta
 * por-Issue (versoes append-only, sem status/update/soft-delete). A unique (issue_id, version)
 * e o guard do ordinal ("a review R aprovou a v3"), declarada na entidade para valer no H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewArtifactPersistenceTest {

    @Autowired
    private PreviewArtifactRepository previews;

    @Test
    void listaAsVersoesDeUmaIssueDaMaisNovaParaAMaisAntiga() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        previews.save(new PreviewArtifact(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), 1, "<html>v1</html>", "hash-v1"));
        previews.save(new PreviewArtifact(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), 2, "<html>v2</html>", "hash-v2"));

        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class);

        assertThat(versoes).extracting(PreviewArtifact::getVersion).containsExactly(2, 1);
        assertThat(versoes.get(0).getContent()).isEqualTo("<html>v2</html>");
        assertThat(versoes.get(0).getSizeBytes()).isEqualTo("<html>v2</html>".getBytes().length);
        // outra company nao ve nada (queries lideradas por company_id, deny-by-default)
        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(UUID.randomUUID(), issueId, PreviewArtifact.class)).isEmpty();
    }

    @Test
    void aUltimaVersaoDaIssueEOAlvoDoDedup() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        previews.save(new PreviewArtifact(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), 1, "<html>v1</html>", "hash-v1"));
        previews.save(new PreviewArtifact(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), 2, "<html>v2</html>", "hash-v2"));

        assertThat(previews.findTopByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId))
                .hasValueSatisfying(ultima -> assertThat(ultima.getContentHash()).isEqualTo("hash-v2"));
    }

    @Test
    void versaoDuplicadaNaMesmaIssueEBarradaPeloBanco() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        previews.save(new PreviewArtifact(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), 1, "<html>a</html>", "hash-a"));

        assertThatThrownBy(() -> previews.saveAndFlush(new PreviewArtifact(UUID.randomUUID(), companyId,
                issueId, agentId, UUID.randomUUID(), 1, "<html>b</html>", "hash-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
