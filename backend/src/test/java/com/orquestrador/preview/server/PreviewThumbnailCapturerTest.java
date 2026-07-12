package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.13 (ADR-0027): o PreviewThumbnailCapturer varre os servidores de preview VIVOS, deriva o run_id
 * do ULTIMO manifest da Issue, e guarda o PNG por-run (pula se ja existe linha — idempotencia
 * por-run). Best-effort NAO-abortante: a falha de um servidor nunca derruba o sweep dos demais e
 * vira {@code THUMBNAIL_CAPTURE_FAILED} ator SYSTEM. Molde do WorkspaceReaperTest: alvo roteirizado
 * entrega os vivos e um resultado de shot fixo — sem daemon, sem browser.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewThumbnailCapturerTest {

    @Autowired
    private PreviewThumbnailCapturer capturer;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private PreviewServerThumbnailRepository thumbnails;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private Cenario cenario() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app v2 com servidor", null).getId();
        return new Cenario(companyId, agentId, issueId);
    }

    private UUID declaraManifest(Cenario cenario, String command, int port) {
        UUID runId = UUID.randomUUID();
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), runId, command, port));
        return runId;
    }

    private ExecutionTarget.PreviewServerLive vivo(Cenario cenario, int hostPort) {
        return new ExecutionTarget.PreviewServerLive(
                "orq_preview_" + cenario.companyId().toString().substring(0, 8),
                cenario.companyId(), cenario.agentId(), cenario.issueId(), hostPort);
    }

    private static byte[] fakePng() {
        byte[] png = new byte[1024];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        return png;
    }

    private List<ActivityLog> failuresFor(UUID companyId, UUID issueId) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(e -> "THUMBNAIL_CAPTURE_FAILED".equals(e.getAction()))
                .filter(e -> issueId.equals(e.getEntityId()))
                .toList();
    }

    @Test
    void fotografaServidorVivoCujoRunAindaNaoTemThumbnail() {
        Cenario cenario = cenario();
        UUID runId = declaraManifest(cenario, "python3 -m http.server 3000", 3000);
        byte[] png = fakePng();
        ScriptedTarget target = new ScriptedTarget(List.of(vivo(cenario, 55001)),
                Map.of(cenario.issueId(), ExecutionTarget.ThumbnailCapture.captured(png)));

        PreviewThumbnailCapturer.CaptureReport report = capturer.sweepTarget(target);

        assertThat(report.captured()).hasSize(1);
        PreviewServerThumbnail salvo = thumbnails
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(cenario.companyId(), cenario.issueId())
                .orElseThrow();
        assertThat(salvo.getRunId()).isEqualTo(runId);
        assertThat(salvo.getContent()).isEqualTo(png);
        assertThat(salvo.getSizeBytes()).isEqualTo(png.length);
        assertThat(failuresFor(cenario.companyId(), cenario.issueId())).isEmpty();
    }

    @Test
    void naoRefotografaRunQueJaTemLinhaENemChamaOShot() {
        Cenario cenario = cenario();
        UUID runId = declaraManifest(cenario, "python3 -m http.server 3000", 3000);
        // ja existe uma linha para este run (idempotencia por-run, B5)
        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), runId, fakePng(), "hash-existente"));
        ScriptedTarget target = new ScriptedTarget(List.of(vivo(cenario, 55001)),
                Map.of(cenario.issueId(), ExecutionTarget.ThumbnailCapture.captured(fakePng())));

        PreviewThumbnailCapturer.CaptureReport report = capturer.sweepTarget(target);

        assertThat(report.skipped()).hasSize(1);
        assertThat(target.screenshotCalls).as("run ja fotografado nao chama o shot").isZero();
        assertThat(thumbnails.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId(), PreviewServerThumbnailMetadata.class)).hasSize(1);
    }

    @Test
    void soFotografaServidoresVIVOSNaoCandidatasComManifest() {
        // Manifest existe (a Issue e candidata), mas NENHUM servidor vivo -> nada e fotografado.
        // O sweep e sobre servidores vivos (world-driven), nao sobre manifests.
        Cenario cenario = cenario();
        declaraManifest(cenario, "python3 -m http.server 3000", 3000);
        ScriptedTarget semVivos = new ScriptedTarget(List.of(), Map.of());

        PreviewThumbnailCapturer.CaptureReport report = capturer.sweepTarget(semVivos);

        assertThat(report.captured()).isEmpty();
        assertThat(thumbnails.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).isEmpty();
    }

    @Test
    void derivaORunIdDoULTIMOManifestDaIssue() {
        Cenario cenario = cenario();
        declaraManifest(cenario, "python3 -m http.server 3000", 3000); // run antigo
        UUID runNovo = declaraManifest(cenario, "python3 -m http.server 8000", 8000); // run vigente
        ScriptedTarget target = new ScriptedTarget(List.of(vivo(cenario, 55001)),
                Map.of(cenario.issueId(), ExecutionTarget.ThumbnailCapture.captured(fakePng())));

        capturer.sweepTarget(target);

        PreviewServerThumbnail salvo = thumbnails
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(cenario.companyId(), cenario.issueId())
                .orElseThrow();
        assertThat(salvo.getRunId()).as("o thumbnail chaveia pelo run do manifest VIGENTE").isEqualTo(runNovo);
    }

    @Test
    void falhaDeUmServidorNaoAbortaOSweepEAuditaComoSystem() {
        Cenario falho = cenario();
        UUID runFalho = declaraManifest(falho, "python3 -m http.server 3000", 3000);
        Cenario ok = cenario();
        declaraManifest(ok, "python3 -m http.server 3000", 3000);
        ScriptedTarget target = new ScriptedTarget(
                List.of(vivo(falho, 55001), vivo(ok, 55002)),
                Map.of(falho.issueId(), ExecutionTarget.ThumbnailCapture.renderFailed("chromium crashou"),
                        ok.issueId(), ExecutionTarget.ThumbnailCapture.captured(fakePng())));

        PreviewThumbnailCapturer.CaptureReport report = capturer.sweepTarget(target);

        // o sweep seguiu: o OK foi fotografado mesmo com o falho antes dele
        assertThat(report.failed()).hasSize(1);
        assertThat(report.captured()).hasSize(1);
        assertThat(thumbnails.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                ok.companyId(), ok.issueId())).isPresent();
        assertThat(thumbnails.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                falho.companyId(), falho.issueId())).as("falho nao persiste").isEmpty();
        // auditoria da falha: ator SYSTEM (ato do pipeline), actor_id nulo, detail com reason e runId
        List<ActivityLog> eventos = failuresFor(falho.companyId(), falho.issueId());
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).getActorType()).isEqualTo(ActorType.SYSTEM.name());
        assertThat(eventos.get(0).getActorId()).isNull();
        assertThat(eventos.get(0).getDetail()).contains("RENDER_FAILED").contains(runFalho.toString());
    }

    @Test
    void capExcedidoNaoPersisteMasAuditaLegivel() {
        Cenario cenario = cenario();
        UUID runId = declaraManifest(cenario, "python3 -m http.server 3000", 3000);
        ScriptedTarget target = new ScriptedTarget(List.of(vivo(cenario, 55001)),
                Map.of(cenario.issueId(),
                        ExecutionTarget.ThumbnailCapture.capExceeded("PNG passou de 2MB")));

        PreviewThumbnailCapturer.CaptureReport report = capturer.sweepTarget(target);

        assertThat(report.failed()).hasSize(1);
        assertThat(thumbnails.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).isEmpty();
        List<ActivityLog> eventos = failuresFor(cenario.companyId(), cenario.issueId());
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).getDetail()).contains("CAP_EXCEEDED").contains(runId.toString());
    }

    /**
     * Alvo roteirizado: entrega uma lista fixa de vivos e um resultado de shot fixo por Issue —
     * sem daemon, sem browser. Conta as chamadas do shot para provar a guarda de idempotencia.
     */
    private static final class ScriptedTarget implements ExecutionTarget {
        private final List<PreviewServerLive> live;
        private final Map<UUID, ThumbnailCapture> resultByIssue;
        private int screenshotCalls = 0;

        ScriptedTarget(List<PreviewServerLive> live, Map<UUID, ThumbnailCapture> resultByIssue) {
            this.live = live;
            this.resultByIssue = resultByIssue;
        }

        @Override
        public Type type() {
            return Type.DOCKER;
        }

        @Override
        public List<PreviewServerLive> listPreviewServers() {
            return live;
        }

        @Override
        public ThumbnailCapture screenshotServer(ScreenshotSpec spec) {
            screenshotCalls++;
            return live.stream()
                    .filter(vivo -> vivo.serverName().equals(spec.serverName()))
                    .map(vivo -> resultByIssue.get(vivo.issueId()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(ThumbnailCapture.renderFailed("sem script para " + spec.serverName()));
        }

        @Override
        public Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PreviewServerStop stopPreviewServer(String serverName) {
            return PreviewServerStop.OK;
        }

        @Override
        public String previewServerLogTail(String serverName, int maxLines) {
            return "";
        }
    }
}
