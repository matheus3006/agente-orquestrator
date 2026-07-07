package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.identity.company.Company;
import com.orquestrador.identity.company.CompanyRepository;
import com.orquestrador.preview.PreviewArtifactMetadata;
import com.orquestrador.preview.PreviewArtifactRepository;
import com.orquestrador.preview.server.PreviewServerThumbnailMetadata;
import com.orquestrador.preview.server.PreviewServerThumbnailRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueStatus;
import com.orquestrador.work.review.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * O TERCEIRO reaper da casa (F.14/ADR-0028): capa o crescimento das 3 tabelas append-only
 * (`preview_server_thumbnail`, `preview_artifact`, `heartbeat_run_event`) coletando a CAUDA
 * superseded de Issues TERMINAIS vencidas — sem apagar o registro durável. Par do
 * {@code WorkspaceReaper}, mas <b>PURE-DB</b>: a remoção é DELETE em lote por repositório, <b>sem</b>
 * tocar o seam {@code ExecutionTarget} (rows não são docker nem filesystem).
 *
 * <p>{@link #sweepOnce()} público (kill-switch + loop das companies) agendado pelo
 * {@code HeartbeatScheduler}; {@link #sweepCompany} é o núcleo profundo testável. Todo o JULGAMENTO
 * do que grada para fora vive na {@link RowRetentionPolicy} (pura); este reaper só lê o banco, chama
 * a política, e executa os DELETEs em chunk.
 *
 * <p><b>Re-read fresco</b> do status da Issue imediatamente antes de processá-la fecha a race com
 * REOPEN/checkout (a mesma disciplina do {@code WorkspaceReaper}/K8s TTL). <b>Best-effort
 * não-abortante em dois níveis</b>: falha numa Issue não aborta a company; falha numa company não
 * aborta as demais. Retenção sempre contra o relógio do BANCO ({@link DatabaseClock}). Auditoria
 * {@code ROWS_COLLECTED} ator {@link ActorType#SYSTEM}: 1 RESUMO por sweep-por-company, gravado APÓS
 * a remoção real e só quando de fato coletou algo.
 */
@Component
public class RowRetentionReaper {

    private static final Logger log = LoggerFactory.getLogger(RowRetentionReaper.class);

    private static final List<IssueStatus> TERMINAL = List.of(IssueStatus.DONE, IssueStatus.CANCELLED);

    private final CompanyRepository companies;
    private final IssueRepository issues;
    private final ReviewRepository reviews;
    private final HeartbeatRunRepository runs;
    private final PreviewServerThumbnailRepository thumbnails;
    private final PreviewArtifactRepository artifacts;
    private final HeartbeatRunEventRepository runEvents;
    private final RowRetentionPolicy policy;
    private final ActivityLogService activityLog;
    private final DatabaseClock clock;
    private final boolean enabled;
    private final Duration retention;
    private final int chunkSize;

    public RowRetentionReaper(CompanyRepository companies, IssueRepository issues, ReviewRepository reviews,
                              HeartbeatRunRepository runs, PreviewServerThumbnailRepository thumbnails,
                              PreviewArtifactRepository artifacts, HeartbeatRunEventRepository runEvents,
                              RowRetentionPolicy policy, ActivityLogService activityLog, DatabaseClock clock,
                              @Value("${orquestrador.rows.gc.enabled:true}") boolean enabled,
                              @Value("${orquestrador.rows.gc.retention-days:30}") long retentionDays,
                              @Value("${orquestrador.rows.gc.chunk-size:500}") int chunkSize) {
        this.companies = companies;
        this.issues = issues;
        this.reviews = reviews;
        this.runs = runs;
        this.thumbnails = thumbnails;
        this.artifacts = artifacts;
        this.runEvents = runEvents;
        this.policy = policy;
        this.activityLog = activityLog;
        this.clock = clock;
        this.enabled = enabled;
        this.retention = Duration.ofDays(retentionDays);
        this.chunkSize = chunkSize;
    }

    /**
     * O que UM sweep coletou numa company (contagens por tabela) — devolvido ao teste e ao resumo.
     * {@code runEvents} é o rastro da passada de Issue (F.14); {@code conversationRunEvents} é o da 2ª
     * passada dos turnos de conversa do CEO (F.15) — buckets separados para a observabilidade distinguir
     * de onde a coleta veio.
     */
    public record SweepReport(int thumbnails, int artifacts, int runEvents, int conversationRunEvents) {

        public int total() {
            return thumbnails + artifacts + runEvents + conversationRunEvents;
        }
    }

    /**
     * O ciclo agendado: kill-switch e varredura de todas as companies. Desligado
     * ({@code orquestrador.rows.gc.enabled=false}) NÃO coleta nada. Falha de uma company vira WARN e
     * não impede as demais (best-effort não-abortante).
     */
    public void sweepOnce() {
        if (!enabled) {
            return;
        }
        for (Company company : companies.findAll()) {
            try {
                SweepReport report = sweepCompany(company.getId());
                if (report.total() > 0) {
                    log.info("GC rows company {}: {} thumbnail, {} artifact, {} run_events, {} "
                                    + "conversation_run_events coletado(s)",
                            company.getId(), report.thumbnails(), report.artifacts(), report.runEvents(),
                            report.conversationRunEvents());
                }
            } catch (RuntimeException exception) {
                log.warn("sweep de GC de rows da company {} falhou — segue nas demais: {}",
                        company.getId(), exception.getMessage());
            }
        }
    }

    /**
     * O núcleo testável: para cada Issue terminal da company, re-lê fresco, aplica a
     * {@link RowRetentionPolicy} e coleta a cauda em chunk. Audita 1 RESUMO {@code ROWS_COLLECTED}
     * (ator SYSTEM) se algo foi coletado. Best-effort por-Issue: uma Issue que falha não aborta as
     * demais.
     */
    SweepReport sweepCompany(UUID companyId) {
        Instant now = clock.now();
        int thumbnailCount = 0;
        int artifactCount = 0;
        int runEventCount = 0;

        for (Issue candidate : issues.findByCompanyIdAndStatusIn(companyId, TERMINAL)) {
            try {
                // Re-read fresco imediatamente antes de coletar: se a Issue reabriu (REOPEN/checkout)
                // entre a listagem e agora, a política a vê não-terminal e não coleta nada.
                Issue fresh = issues.findById(candidate.getId()).orElse(null);
                if (fresh == null) {
                    continue;
                }
                RowRetentionPolicy.Decision decision = decideFor(fresh, now);
                thumbnailCount += deleteInChunks(decision.thumbnailIdsToCollect(), thumbnails::deleteByIdIn);
                artifactCount += deleteInChunks(decision.artifactIdsToCollect(), artifacts::deleteByIdIn);
                if (decision.collectRunEvents()) {
                    runEventCount += collectRunEvents(companyId, fresh.getId());
                }
            } catch (RuntimeException exception) {
                log.warn("coleta de rows da Issue {} falhou — segue nas demais: {}",
                        candidate.getId(), exception.getMessage());
            }
        }

        // 2ª passada (F.15): o rastro dos turnos de conversa do CEO (Runs sem Issue) vencidos. Guardada
        // à parte para que uma falha aqui não aborte a company NEM esconda a auditoria da passada de
        // Issue já removida (B7 — deleção sem rastro é o que não pode acontecer).
        int conversationRunEventCount = 0;
        try {
            conversationRunEventCount = collectConversationRunEvents(companyId, now.minus(retention));
        } catch (RuntimeException exception) {
            log.warn("coleta de run_events de conversa da company {} falhou — segue: {}",
                    companyId, exception.getMessage());
        }

        SweepReport report = new SweepReport(thumbnailCount, artifactCount, runEventCount,
                conversationRunEventCount);
        if (report.total() > 0) {
            recordCollected(companyId, report);
        }
        return report;
    }

    /** Resolve as rows candidatas da Issue + os pins de review e delega o julgamento à política pura. */
    private RowRetentionPolicy.Decision decideFor(Issue issue, Instant now) {
        UUID companyId = issue.getCompanyId();
        UUID issueId = issue.getId();

        List<RowRetentionPolicy.ThumbnailRef> thumbnailRefs = thumbnails
                .findByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId,
                        PreviewServerThumbnailMetadata.class)
                .stream()
                .map(meta -> new RowRetentionPolicy.ThumbnailRef(meta.getId(), meta.getCreatedAt()))
                .toList();
        List<RowRetentionPolicy.ArtifactRef> artifactRefs = artifacts
                .findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifactMetadata.class)
                .stream()
                .map(meta -> new RowRetentionPolicy.ArtifactRef(meta.getId(), meta.getVersion(), meta.getRunId()))
                .toList();
        Set<UUID> reviewPinnedRunIds = Set.copyOf(reviews.findRunIdsByCompanyIdAndIssueId(companyId, issueId));

        return policy.decide(new RowRetentionPolicy.IssueRows(issue.getStatus(), issue.getUpdatedAt(),
                thumbnailRefs, artifactRefs, reviewPinnedRunIds), now, retention);
    }

    /**
     * Coleta os eventos de TODOS os runs da Issue terminal-vencida (a passada F.14). O run FICA — é hub
     * de FK; só os filhos-evento vão.
     */
    private int collectRunEvents(UUID companyId, UUID issueId) {
        return drainEventsOf(runs.findByIssueIdAndCompanyIdOrderByCreatedAtDesc(issueId, companyId)
                .stream()
                .map(HeartbeatRun::getId)
                .toList());
    }

    /**
     * A 2ª passada (F.15): o rastro (run_events) dos Runs que são TURNOS de conversa do CEO vencidos —
     * mirados por {@code conversation_turn.run_id} (D4), carimbados pelo {@code COALESCE(finished_at,
     * created_at)} do Run (D2, o cutoff já resolvido pelo chamador contra o {@link DatabaseClock}). O
     * Run e o texto do turno (em {@code conversation_turn}) FICAM; só os filhos-evento vão.
     */
    private int collectConversationRunEvents(UUID companyId, Instant cutoff) {
        return drainEventsOf(runs.findConversationTurnRunIdsExpired(companyId, cutoff));
    }

    /**
     * O mecanismo compartilhado das duas passadas: drena em chunk os run_events dos runs dados.
     * Count-heavy (o firehose do StepTrace) — em vez de materializar milhões de ids, pagina sempre a
     * página 0 porque o DELETE anterior já removeu as anteriores, até esvaziar. Lista vazia = no-op (o
     * {@code in :runIds} do JPQL não aceita coleção vazia).
     */
    private int drainEventsOf(List<UUID> runIds) {
        if (runIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        List<UUID> page;
        while (!(page = runEvents.findIdsByRunIdIn(runIds, PageRequest.of(0, chunkSize))).isEmpty()) {
            removed += runEvents.deleteByIdIn(page);
        }
        return removed;
    }

    /** DELETE em lote respeitando o chunk (as listas de cauda são pequenas, mas o teto é o mesmo). */
    private int deleteInChunks(List<UUID> ids, ToIntFunction<Collection<UUID>> deleteBatch) {
        int removed = 0;
        for (int start = 0; start < ids.size(); start += chunkSize) {
            List<UUID> chunk = ids.subList(start, Math.min(start + chunkSize, ids.size()));
            removed += deleteBatch.applyAsInt(chunk);
        }
        return removed;
    }

    private void recordCollected(UUID companyId, SweepReport report) {
        // Auditoria APÓS a remoção REAL (só o que de fato coletou audita). Falha aqui não desfaz a
        // remoção — registra em ERROR (raro) para investigação, sem derrubar o sweep dos demais.
        try {
            activityLog.record(companyId, ActorType.SYSTEM, null, ActivityAction.ROWS_COLLECTED,
                    "company", companyId, Map.of("thumbnail", report.thumbnails(),
                            "artifact", report.artifacts(), "run_events", report.runEvents(),
                            "conversation_run_events", report.conversationRunEvents()));
        } catch (RuntimeException exception) {
            log.error("rows coletadas mas a auditoria ROWS_COLLECTED falhou (company {}): {}",
                    companyId, exception.getMessage());
        }
    }
}
