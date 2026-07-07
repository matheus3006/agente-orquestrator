package com.orquestrador.execution.runtime;

import com.orquestrador.execution.runtime.RowRetentionPolicy.ArtifactRef;
import com.orquestrador.execution.runtime.RowRetentionPolicy.Decision;
import com.orquestrador.execution.runtime.RowRetentionPolicy.IssueRows;
import com.orquestrador.execution.runtime.RowRetentionPolicy.ThumbnailRef;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.14 (ADR-0028) — o CORAÇÃO da fatia, PURO (sem DB): dado o status/updated_at da Issue + as rows
 * candidatas + os run_ids fixados por review + now/retenção, decide o que COLETA (a cauda superseded)
 * e o que MANTÉM (o vigente por-Issue + o fixado por review). B3: "eterno = vigente + fixado, NÃO cada
 * row"; B2: gate terminal+vencida (âncora TTL-desde-terminal contra o relógio do banco).
 */
class RowRetentionPolicyTest {

    private final RowRetentionPolicy policy = new RowRetentionPolicy();

    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Instant VENCIDA = NOW.minus(Duration.ofDays(31)); // updated_at + 30d < now
    private static final Instant RECENTE = NOW.minus(Duration.ofDays(5));   // dentro da retenção

    // ===== Forma A: keep-current-per-issue (thumbnail) =====

    @Test
    void mantemOUltimoThumbnailDaIssueEColetaOsAnteriores() {
        UUID antigo = UUID.randomUUID();
        UUID meio = UUID.randomUUID();
        UUID vigente = UUID.randomUUID(); // o de MAIOR created_at = o que a UI exibe (findTop desc)

        IssueRows rows = new IssueRows(IssueStatus.DONE, VENCIDA,
                List.of(new ThumbnailRef(antigo, NOW.minus(Duration.ofHours(3))),
                        new ThumbnailRef(vigente, NOW.minus(Duration.ofHours(1))),
                        new ThumbnailRef(meio, NOW.minus(Duration.ofHours(2)))),
                List.of(), Set.of());

        Decision decision = policy.decide(rows, NOW, RETENTION);

        assertThat(decision.thumbnailIdsToCollect()).containsExactlyInAnyOrder(antigo, meio);
        assertThat(decision.thumbnailIdsToCollect()).doesNotContain(vigente);
    }

    @Test
    void thumbnailUnicoDaIssueNuncaEColetado() {
        UUID unico = UUID.randomUUID();
        IssueRows rows = new IssueRows(IssueStatus.DONE, VENCIDA,
                List.of(new ThumbnailRef(unico, NOW.minus(Duration.ofHours(1)))),
                List.of(), Set.of());

        assertThat(policy.decide(rows, NOW, RETENTION).thumbnailIdsToCollect()).isEmpty();
    }

    // ===== Forma A: keep-current-per-issue + pin de review (artifact) =====

    @Test
    void mantemAMaiorVersionDeArtifactEColetaAsSuperseded() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID(); // maior version = o que a UI mostra por padrão

        IssueRows rows = new IssueRows(IssueStatus.DONE, VENCIDA, List.of(),
                List.of(new ArtifactRef(v1, 1, UUID.randomUUID()),
                        new ArtifactRef(v2, 2, UUID.randomUUID()),
                        new ArtifactRef(v3, 3, UUID.randomUUID())),
                Set.of());

        Decision decision = policy.decide(rows, NOW, RETENTION);

        assertThat(decision.artifactIdsToCollect()).containsExactlyInAnyOrder(v1, v2);
        assertThat(decision.artifactIdsToCollect()).doesNotContain(v3);
    }

    @Test
    void mantemAVersionDeArtifactFIXADAPorReviewMesmoNaoSendoAMaior() {
        UUID runFixado = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID(); // fixada por review (run_id referenciado por uma review)
        UUID v3 = UUID.randomUUID(); // maior version = vigente

        IssueRows rows = new IssueRows(IssueStatus.DONE, VENCIDA, List.of(),
                List.of(new ArtifactRef(v1, 1, UUID.randomUUID()),
                        new ArtifactRef(v2, 2, runFixado),
                        new ArtifactRef(v3, 3, UUID.randomUUID())),
                Set.of(runFixado));

        Decision decision = policy.decide(rows, NOW, RETENTION);

        // v3 (vigente) e v2 (fixada por review) sobrevivem; só a v1 superseded não-fixada é coletada.
        assertThat(decision.artifactIdsToCollect()).containsExactly(v1);
        assertThat(decision.artifactIdsToCollect()).doesNotContain(v2, v3);
    }

    // ===== Forma B: run_event por-run de Issue terminal-vencida =====

    @Test
    void coletaRunEventsQuandoAIssueETerminalVencida() {
        IssueRows rows = new IssueRows(IssueStatus.DONE, VENCIDA, List.of(), List.of(), Set.of());
        assertThat(policy.decide(rows, NOW, RETENTION).collectRunEvents()).isTrue();
    }

    @Test
    void naoColetaRunEventsQuandoAIssueNaoEColecionavel() {
        IssueRows naoTerminal = new IssueRows(IssueStatus.IN_PROGRESS, VENCIDA, List.of(), List.of(), Set.of());
        IssueRows naoVencida = new IssueRows(IssueStatus.DONE, RECENTE, List.of(), List.of(), Set.of());

        assertThat(policy.decide(naoTerminal, NOW, RETENTION).collectRunEvents()).isFalse();
        assertThat(policy.decide(naoVencida, NOW, RETENTION).collectRunEvents()).isFalse();
    }

    // ===== O gate terminal+vencida protege TODAS as três tabelas =====

    @Test
    void issueNaoTerminalNuncaColetaNada() {
        IssueRows rows = new IssueRows(IssueStatus.IN_PROGRESS, VENCIDA,
                List.of(new ThumbnailRef(UUID.randomUUID(), NOW.minus(Duration.ofHours(3))),
                        new ThumbnailRef(UUID.randomUUID(), NOW.minus(Duration.ofHours(1)))),
                List.of(new ArtifactRef(UUID.randomUUID(), 1, UUID.randomUUID()),
                        new ArtifactRef(UUID.randomUUID(), 2, UUID.randomUUID())),
                Set.of());

        Decision decision = policy.decide(rows, NOW, RETENTION);

        assertThat(decision.thumbnailIdsToCollect()).isEmpty();
        assertThat(decision.artifactIdsToCollect()).isEmpty();
        assertThat(decision.collectRunEvents()).isFalse();
    }

    @Test
    void issueTerminalMasDentroDaRetencaoNuncaColetaNada() {
        IssueRows rows = new IssueRows(IssueStatus.DONE, RECENTE,
                List.of(new ThumbnailRef(UUID.randomUUID(), NOW.minus(Duration.ofHours(3))),
                        new ThumbnailRef(UUID.randomUUID(), NOW.minus(Duration.ofHours(1)))),
                List.of(new ArtifactRef(UUID.randomUUID(), 1, UUID.randomUUID()),
                        new ArtifactRef(UUID.randomUUID(), 2, UUID.randomUUID())),
                Set.of());

        Decision decision = policy.decide(rows, NOW, RETENTION);

        assertThat(decision.thumbnailIdsToCollect()).isEmpty();
        assertThat(decision.artifactIdsToCollect()).isEmpty();
        assertThat(decision.collectRunEvents()).isFalse();
    }

    @Test
    void issueCancelledVencidaTambemEColecionavel() {
        UUID vigente = UUID.randomUUID();
        UUID antigo = UUID.randomUUID();
        IssueRows rows = new IssueRows(IssueStatus.CANCELLED, VENCIDA,
                List.of(new ThumbnailRef(antigo, NOW.minus(Duration.ofHours(2))),
                        new ThumbnailRef(vigente, NOW.minus(Duration.ofHours(1)))),
                List.of(), Set.of());

        Decision decision = policy.decide(rows, NOW, RETENTION);

        assertThat(decision.thumbnailIdsToCollect()).containsExactly(antigo);
        assertThat(decision.collectRunEvents()).isTrue();
    }

    @Test
    void bordaNaExpiracao_updatedAtExatamenteNoCutoffNaoEVencida() {
        // cutoff = now - retention; vencida exige updated_at ESTRITAMENTE antes do cutoff (isBefore).
        Instant noCutoff = NOW.minus(RETENTION);
        IssueRows rows = new IssueRows(IssueStatus.DONE, noCutoff, List.of(), List.of(), Set.of());
        assertThat(policy.decide(rows, NOW, RETENTION).collectRunEvents()).isFalse();
    }
}
