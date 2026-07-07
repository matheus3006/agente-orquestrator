package com.orquestrador.execution;

import com.orquestrador.execution.runtime.RowRetentionReaper;
import com.orquestrador.execution.runtime.WorkspaceReaper;
import com.orquestrador.preview.server.PreviewServerSupervisor;
import com.orquestrador.preview.server.PreviewThumbnailCapturer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aciona o motor periodicamente. Desligavel via {@code orquestrador.scheduling.enabled=false}
 * (os testes desligam para drivar dispatcher/reaper manualmente, de forma deterministica).
 */
@Component
@ConditionalOnProperty(name = "orquestrador.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class HeartbeatScheduler {

    private final HeartbeatDispatcher dispatcher;
    private final LeaseReaper reaper;
    private final PreviewServerSupervisor previewServers;
    private final WorkspaceReaper workspaceReaper;
    private final RowRetentionReaper rowRetentionReaper;
    private final PreviewThumbnailCapturer thumbnailCapturer;

    public HeartbeatScheduler(HeartbeatDispatcher dispatcher, LeaseReaper reaper,
                              PreviewServerSupervisor previewServers, WorkspaceReaper workspaceReaper,
                              RowRetentionReaper rowRetentionReaper,
                              PreviewThumbnailCapturer thumbnailCapturer) {
        this.dispatcher = dispatcher;
        this.reaper = reaper;
        this.previewServers = previewServers;
        this.workspaceReaper = workspaceReaper;
        this.rowRetentionReaper = rowRetentionReaper;
        this.thumbnailCapturer = thumbnailCapturer;
    }

    @Scheduled(fixedDelayString = "${orquestrador.heartbeat.dispatch-ms:1000}")
    public void dispatch() {
        dispatcher.dispatchOnce();
    }

    @Scheduled(fixedDelayString = "${orquestrador.heartbeat.reap-ms:5000}")
    public void reap() {
        reaper.reapOnce();
    }

    /** F.10 (ADR-0023 §3): ceifa preview servers sem heartbeat do board ha mais de um TTL. */
    @Scheduled(fixedDelayString = "${orquestrador.preview.server.reap-ms:60000}")
    public void reapPreviewServers() {
        previewServers.reapOnce();
    }

    /**
     * F.12 (ADR-0026): coleta workspaces por-Issue de Issues terminais vencidas (retencao de dias
     * nao pede varredura de minuto — default 1h). Kill-switch dentro do proprio reaper.
     */
    @Scheduled(fixedDelayString = "${orquestrador.workspace.gc.sweep-ms:3600000}")
    public void reapWorkspaces() {
        workspaceReaper.sweepOnce();
    }

    /**
     * F.14 (ADR-0028): o 3º reaper (PURE-DB) capa a cauda append-only das 3 tabelas
     * (thumbnail/artifact/run_event) de Issues terminais vencidas — TTL de dias, varredura de 1h.
     * Kill-switch dentro do próprio reaper; best-effort não-abortante por company.
     */
    @Scheduled(fixedDelayString = "${orquestrador.rows.gc.sweep-ms:3600000}")
    public void reapRows() {
        rowRetentionReaper.sweepOnce();
    }

    /**
     * F.13 (ADR-0027): fotografa os servidores de preview VIVOS 1×/ciclo (~60s, o mesmo passo do
     * reaper de preview) — na janela PRE-GC, pulando runs ja fotografados. Best-effort NAO-abortante
     * dentro do proprio capturador; o shot nunca atrasa nem quebra a subida.
     */
    @Scheduled(fixedDelayString = "${orquestrador.preview.thumbnail.sweep-ms:60000}")
    public void capturePreviewThumbnails() {
        thumbnailCapturer.captureOnce();
    }
}
