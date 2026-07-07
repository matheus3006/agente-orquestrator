package com.orquestrador.execution.runtime;

import com.orquestrador.work.issue.IssueStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * O CORAÇÃO da F.14 (ADR-0028 §B4): a política PURA (sem DB) que decide o que a cauda append-only
 * de uma Issue GRADUA para fora vs o que fica ETERNO. Módulo profundo — todo o julgamento vive aqui
 * atrás de um {@link #decide} pequeno; o {@code RowRetentionReaper} só lê o banco, chama isto, e
 * executa os DELETEs.
 *
 * <p><b>Gate (B2)</b>: só coleta de Issue <b>TERMINAL</b> (DONE/CANCELLED) <b>vencida</b> — {@code
 * updated_at} estritamente antes de {@code now - retention} (a mesma âncora TTL-desde-terminal da
 * F.12, comparada contra o relógio do BANCO pelo chamador via {@code DatabaseClock}). Fora do gate:
 * mantém tudo (direção de falha conservadora — toque posterior só ADIA).
 *
 * <p><b>Eterno = vigente + fixado, NÃO cada row (B3)</b>. Duas formas espelhando os perfis de acúmulo:
 * <ul>
 *   <li><b>thumbnail</b>: mantém o ÚLTIMO por-Issue (máx {@code created_at} — a mesma regra de
 *       exibição {@code findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc}); coleta os anteriores.
 *       Sem pin de review (thumbnail é só-exibição — nada o referencia).</li>
 *   <li><b>artifact</b>: mantém a MAIOR {@code version} (o que a UI mostra; única por Issue) MAIS
 *       qualquer versão cujo {@code run_id} seja fixado por uma review; coleta o resto.</li>
 *   <li><b>run_event</b>: quando a Issue é colecionável, coleta os eventos de TODOS os runs dela (o
 *       run fica — é hub de FK; só os filhos-evento vão). A decisão aqui é o gate booleano; o reaper
 *       resolve os runs e deleta em lote (count-heavy — não materializa ids na política).</li>
 * </ul>
 */
@Component
public class RowRetentionPolicy {

    /** Metadado mínimo de um thumbnail candidato — o {@code id} e o {@code created_at} do vigente. */
    public record ThumbnailRef(UUID id, Instant createdAt) {
    }

    /** Metadado mínimo de um artifact candidato — {@code id}, a {@code version} ordinal, o {@code runId}. */
    public record ArtifactRef(UUID id, int version, UUID runId) {
    }

    /** O retrato de uma Issue para a política: seu gate + as rows candidatas + os pins de review. */
    public record IssueRows(IssueStatus status, Instant updatedAt, List<ThumbnailRef> thumbnails,
                            List<ArtifactRef> artifacts, Set<UUID> reviewPinnedRunIds) {
    }

    /** O veredito: ids a coletar em cada tabela byte-heavy + o gate count-heavy dos run_events. */
    public record Decision(List<UUID> thumbnailIdsToCollect, List<UUID> artifactIdsToCollect,
                           boolean collectRunEvents) {

        static Decision keepAll() {
            return new Decision(List.of(), List.of(), false);
        }

        public boolean collectsNothing() {
            return thumbnailIdsToCollect.isEmpty() && artifactIdsToCollect.isEmpty() && !collectRunEvents;
        }
    }

    public Decision decide(IssueRows rows, Instant now, Duration retention) {
        if (!collectible(rows.status(), rows.updatedAt(), now, retention)) {
            return Decision.keepAll();
        }
        return new Decision(supersededThumbnails(rows.thumbnails()),
                supersededArtifacts(rows.artifacts(), rows.reviewPinnedRunIds()), true);
    }

    private boolean collectible(IssueStatus status, Instant updatedAt, Instant now, Duration retention) {
        return isTerminal(status) && updatedAt.isBefore(now.minus(retention));
    }

    /** Coleta todos menos o vigente (máx {@code created_at}; desempate por id p/ determinismo). */
    private List<UUID> supersededThumbnails(List<ThumbnailRef> thumbnails) {
        if (thumbnails.size() <= 1) {
            return List.of();
        }
        UUID vigente = thumbnails.stream()
                .max(Comparator.comparing(ThumbnailRef::createdAt).thenComparing(ThumbnailRef::id))
                .map(ThumbnailRef::id)
                .orElseThrow();
        return thumbnails.stream()
                .map(ThumbnailRef::id)
                .filter(id -> !id.equals(vigente))
                .toList();
    }

    /** Coleta todos menos a maior version (o vigente) e as versões fixadas por review. */
    private List<UUID> supersededArtifacts(List<ArtifactRef> artifacts, Set<UUID> reviewPinnedRunIds) {
        if (artifacts.isEmpty()) {
            return List.of();
        }
        int vigenteVersion = artifacts.stream().mapToInt(ArtifactRef::version).max().orElseThrow();
        return artifacts.stream()
                .filter(a -> a.version() != vigenteVersion)
                .filter(a -> !reviewPinnedRunIds.contains(a.runId()))
                .map(ArtifactRef::id)
                .toList();
    }

    private static boolean isTerminal(IssueStatus status) {
        return status == IssueStatus.DONE || status == IssueStatus.CANCELLED;
    }
}
