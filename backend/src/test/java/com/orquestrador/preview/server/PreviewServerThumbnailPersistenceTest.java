package com.orquestrador.preview.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.13 (ADR-0027): preview_server_thumbnail guarda o PNG DURAVEL do app v2 fotografado enquanto o
 * servidor estava vivo — {@code bytea} por-run, append-only (irmao binario da foto v1 do
 * preview_artifact, e do manifest V21 que tambem ganhou tabela propria). O vigente para exibir e o
 * ULTIMO da Issue; a guarda de idempotencia do capturador e a existencia de linha para o run.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerThumbnailPersistenceTest {

    @Autowired
    private PreviewServerThumbnailRepository thumbnails;

    /** Um PNG "de verdade" > 255 bytes: pega qualquer coluna binaria truncada a VARBINARY(255). */
    private static byte[] fakePng(int size) {
        byte[] bytes = new byte[size];
        // assinatura PNG so para nao ser um blob de zeros indistinguivel
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        for (int i = 4; i < size; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
    }

    @Test
    void roundTripDosBytesPorIdLideradoPorCompany() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        byte[] png = fakePng(4096);

        PreviewServerThumbnail salvo = thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(),
                companyId, issueId, agentId, UUID.randomUUID(), png, "hash-1"));

        PreviewServerThumbnail lido = thumbnails.findByIdAndCompanyId(salvo.getId(), companyId).orElseThrow();
        assertThat(lido.getContent()).isEqualTo(png);
        assertThat(lido.getSizeBytes()).isEqualTo(4096);
        assertThat(lido.getContentHash()).isEqualTo("hash-1");
        // outra company nao ve por id (deny-by-default: queries lideradas por company_id)
        assertThat(thumbnails.findByIdAndCompanyId(salvo.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void oVigenteParaExibirEOUltimoDaIssue() {
        // Por-run, append-only: um run novo fotografa de novo -> linha nova; a Issue mostra a ULTIMA
        // (espelha o findTop do manifest V21 e o dedup-por-ultima do preview_artifact).
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), fakePng(512), "hash-antiga"));
        UUID runNovo = UUID.randomUUID();
        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), companyId, issueId, agentId,
                runNovo, fakePng(1024), "hash-nova"));

        PreviewServerThumbnail vigente = thumbnails
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId).orElseThrow();
        assertThat(vigente.getRunId()).isEqualTo(runNovo);
        assertThat(vigente.getContentHash()).isEqualTo("hash-nova");
    }

    @Test
    void existenciaPorRunEAGuardaDeIdempotenciaDoCapturador() {
        // O capturador PULA se ja existe linha para aquele run_id (guarda por-run, B5).
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        assertThat(thumbnails.existsByCompanyIdAndRunId(companyId, runId)).isFalse();
        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), companyId, issueId,
                UUID.randomUUID(), runId, fakePng(300), "hash-run"));

        assertThat(thumbnails.existsByCompanyIdAndRunId(companyId, runId)).isTrue();
        // run de outra company nao colide (a guarda e liderada por company_id)
        assertThat(thumbnails.existsByCompanyIdAndRunId(UUID.randomUUID(), runId)).isFalse();
    }

    @Test
    void metadadosDaIssueNaoCarregamOsBytes() {
        // A lista de versoes le METADADOS (o content bytea nao viaja Postgres->JVM na lista) —
        // molde do PreviewArtifactMetadata; o conteudo sai so pelo endpoint por id.
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), fakePng(2048), "hash-meta"));

        List<PreviewServerThumbnailMetadata> metadados = thumbnails
                .findByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId,
                        PreviewServerThumbnailMetadata.class);

        assertThat(metadados).hasSize(1);
        assertThat(metadados.get(0).getSizeBytes()).isEqualTo(2048);
        assertThat(metadados.get(0).getContentHash()).isEqualTo("hash-meta");
    }
}
