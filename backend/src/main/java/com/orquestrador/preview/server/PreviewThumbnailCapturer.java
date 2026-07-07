package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O modulo profundo do thumbnail do preview v2 (F.13/ADR-0027): o 5º container da familia. Par do
 * {@code WorkspaceReaper}/{@code PreviewServerSupervisor.reapOnce()}: {@link #captureOnce()} publico
 * agendado pelo {@code HeartbeatScheduler}; {@link #sweepTarget} e o nucleo profundo testavel.
 *
 * <p><b>World-driven, na janela pre-GC</b> (ADR-0027 §B2/§B5): 1×/ciclo lista os servidores de
 * preview VIVOS do alvo (a mesma listagem que o reaper da 0023 faz), e para cada um DERIVA o run_id
 * do ULTIMO manifest da Issue ({@code preview_server_manifest} V21 — o mesmo que o start usa),
 * <b>pula se ja existe linha</b> para aquele run (guarda de idempotencia por-run, mais limpa que
 * dedup por hash), senao fotografa pelo seam ({@link ExecutionTarget#screenshotServer}) e persiste o
 * PNG em {@code preview_server_thumbnail}.
 *
 * <p><b>Best-effort NAO-abortante</b> (o invariante mais importante da fatia): a falha de UM servidor
 * nunca aborta o sweep dos demais nem toca a subida — o shot e observacao pura, nao participante da
 * maquina de estados (ADR-0021 §6, ADR-0023 §2). SO a falha vira evento
 * {@code THUMBNAIL_CAPTURE_FAILED} (ator {@code SYSTEM} — ato do pipeline, reusa o ator da F.12;
 * detail so-IDs/enum, ADR-0009). A ROW e o registro do sucesso (como a foto v1) — sem evento de
 * captura redundante. Retenta a cada ciclo enquanto o servidor viver (o TTL de 15min da 0023 limita
 * a insistencia sozinho).
 */
@Component
public class PreviewThumbnailCapturer {

    private static final Logger log = LoggerFactory.getLogger(PreviewThumbnailCapturer.class);

    /** Cap do PNG (protege o banco e o serving). Espelha o cap da foto v1; estourou = CAP_EXCEEDED. */
    static final long MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    /**
     * O RELEASE do Alpine PINADO na imagem-shot (F.13/ADR-0027) — o {@code PreviewShotImagePinTest}
     * trava Dockerfile ({@code FROM alpine:<release>}) <-> esta constante (defesa {@code --bare},
     * como o {@code EngineContainer} trava o CLI). Base = alpine + Chromium COMPLETO do Alpine (o
     * smoke reprovou o {@code chromedp/headless-shell} da deep-research: e CDP-only, sem o one-shot
     * {@code --screenshot} que o seam usa — ADR-0027 §B3/§E). O chromium FLUTUA dentro do release
     * (security-update do Alpine — CVE patching desejavel, nao risco de --bare); o pino e a base.
     */
    static final String SHOT_ALPINE_RELEASE = "3.21";

    /** A imagem-shot da casa (tag = base Alpine pinada) — default do property, buildada pelo build.sh. */
    static final String SHOT_IMAGE = "orq-preview-shot:alpine" + SHOT_ALPINE_RELEASE;

    private final PreviewServerManifestRepository manifests;
    private final PreviewServerThumbnailRepository thumbnails;
    private final ExecutionTargets targets;
    private final ActivityLogService activityLog;
    private final String shotImage;
    private final int windowWidth;
    private final int windowHeight;
    private final int virtualTimeBudgetMs;

    public PreviewThumbnailCapturer(PreviewServerManifestRepository manifests,
                                    PreviewServerThumbnailRepository thumbnails, ExecutionTargets targets,
                                    ActivityLogService activityLog,
                                    @Value("${orquestrador.preview.thumbnail.shot-image:" + SHOT_IMAGE + "}") String shotImage,
                                    @Value("${orquestrador.preview.thumbnail.window-width:1280}") int windowWidth,
                                    @Value("${orquestrador.preview.thumbnail.window-height:800}") int windowHeight,
                                    @Value("${orquestrador.preview.thumbnail.virtual-time-budget-ms:5000}") int virtualTimeBudgetMs) {
        this.manifests = manifests;
        this.thumbnails = thumbnails;
        this.targets = targets;
        this.activityLog = activityLog;
        this.shotImage = shotImage;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.virtualTimeBudgetMs = virtualTimeBudgetMs;
    }

    /** O que UM ciclo fez num alvo — devolvido para o teste; efeitos (rows/eventos) ja aplicados. */
    public record CaptureReport(List<String> captured, List<String> skipped, List<String> failed) {
    }

    /**
     * O ciclo agendado: varre os dois alvos. Falha de um alvo (ex.: daemon fora) vira WARN e nao
     * impede o outro (best-effort no nivel do alvo, espelho do {@code WorkspaceReaper.sweepOnce}).
     */
    public void captureOnce() {
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            try {
                CaptureReport report = sweepTarget(targets.forType(type));
                if (!report.captured().isEmpty() || !report.failed().isEmpty()) {
                    log.info("thumbnails {}: {} capturado(s), {} pulado(s), {} falha(s)", type,
                            report.captured().size(), report.skipped().size(), report.failed().size());
                }
            } catch (RuntimeException exception) {
                log.warn("sweep de thumbnail do alvo {} falhou: {}", type, exception.getMessage());
            }
        }
    }

    /**
     * O nucleo testavel: para cada servidor VIVO do alvo, deriva o run do manifest vigente da Issue,
     * pula quem ja tem linha, fotografa e persiste. Um servidor falho vira WARN + evento e o sweep
     * SEGUE nos demais (best-effort NAO-abortante — o try/catch por-servidor e o coracao do invariante).
     */
    CaptureReport sweepTarget(ExecutionTarget target) {
        List<String> captured = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (ExecutionTarget.PreviewServerLive vivo : target.listPreviewServers()) {
            try {
                captureFor(target, vivo, captured, skipped, failed);
            } catch (RuntimeException exception) {
                // best-effort NAO-abortante: um servidor nunca derruba o sweep dos demais
                log.warn("captura de thumbnail falhou inesperadamente para {}: {}",
                        vivo.serverName(), exception.getMessage());
                recordFailure(vivo, null, "CAPTURE_ERROR");
                failed.add(vivo.serverName());
            }
        }
        return new CaptureReport(captured, skipped, failed);
    }

    private void captureFor(ExecutionTarget target, ExecutionTarget.PreviewServerLive vivo,
                            List<String> captured, List<String> skipped, List<String> failed) {
        PreviewServerManifest manifest = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(vivo.companyId(), vivo.issueId())
                .orElse(null);
        if (manifest == null) {
            // vivo sem manifest (nao deveria — o start exige manifest); sem run_id nao ha chave por-run
            log.warn("preview server vivo {} sem manifest — sem run para chavear o thumbnail; pula",
                    vivo.serverName());
            skipped.add(vivo.serverName());
            return;
        }
        if (thumbnails.existsByCompanyIdAndRunId(vivo.companyId(), manifest.getRunId())) {
            skipped.add(vivo.serverName()); // idempotencia por-run: ja fotografamos este run
            return;
        }

        ExecutionTarget.ScreenshotSpec spec = new ExecutionTarget.ScreenshotSpec(
                vivo.serverName(), manifest.getPort(), vivo.hostPort(),
                shotImage, windowWidth, windowHeight, virtualTimeBudgetMs, MAX_CONTENT_BYTES);
        ExecutionTarget.ThumbnailCapture shot = target.screenshotServer(spec);
        if (!shot.captured()) {
            log.warn("shot do preview {} nao capturou ({}): {}",
                    vivo.serverName(), shot.outcome(), shot.error());
            recordFailure(vivo, manifest.getRunId(), shot.outcome().name());
            failed.add(vivo.serverName());
            return;
        }
        persist(vivo, manifest.getRunId(), shot.bytes());
        captured.add(vivo.serverName());
    }

    /** A ROW e o registro do sucesso — falha aqui e PERSIST_FAILED (legivel), nunca excecao pro sweep. */
    private void persist(ExecutionTarget.PreviewServerLive vivo, UUID runId, byte[] bytes) {
        try {
            thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), vivo.companyId(),
                    vivo.issueId(), vivo.agentId(), runId, bytes, sha256Hex(bytes)));
            log.info("thumbnail do preview {} capturado (run {}, {} bytes)",
                    vivo.serverName(), runId, bytes.length);
        } catch (RuntimeException exception) {
            log.warn("persistencia do thumbnail falhou ({}, run {}): {}",
                    vivo.serverName(), runId, exception.getMessage());
            recordFailure(vivo, runId, "PERSIST_FAILED");
        }
    }

    /** Evento SO na falha (ADR-0027 §B5): ator SYSTEM, detail so-IDs/enum (runId + reason). */
    private void recordFailure(ExecutionTarget.PreviewServerLive vivo, UUID runId, String reason) {
        try {
            Map<String, Object> detail = runId != null
                    ? Map.of("runId", runId, "reason", reason)
                    : Map.of("reason", reason);
            activityLog.record(vivo.companyId(), ActorType.SYSTEM, null,
                    ActivityAction.THUMBNAIL_CAPTURE_FAILED, "issue", vivo.issueId(), detail);
        } catch (RuntimeException exception) {
            log.warn("registro de THUMBNAIL_CAPTURE_FAILED tambem falhou ({}): {}",
                    vivo.serverName(), exception.getMessage());
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel", exception);
        }
    }
}
