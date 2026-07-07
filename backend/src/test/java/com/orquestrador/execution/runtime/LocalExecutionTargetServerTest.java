package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023 §8): o adapter LOCAL do par start/stop — spawn no workDir (dev-only, mesma
 * classe de risco do run LOCAL), registro EM MEMORIA (sem reaper de PID orfao; destroy no
 * shutdown). A porta do host E a do manifest (sem remapeamento). Mesma interface do DOCKER:
 * quem poll-a TCP e o modulo preview.server, nao o alvo.
 */
class LocalExecutionTargetServerTest {

    private final LocalExecutionTarget target = new LocalExecutionTarget();

    private ExecutionTarget.PreviewServerSpec spec(String serverName, Path workDir, String command,
                                                   int port) {
        return new ExecutionTarget.PreviewServerSpec(serverName, workDir, "vol-ignorado", null,
                command, port, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "512m", "1", "256");
    }

    @AfterEach
    void derrubaTudo() {
        target.listPreviewServers().forEach(vivo -> target.stopPreviewServer(vivo.serverName()));
        System.clearProperty(LocalExecutionTarget.LOCAL_BROWSER_PROPERTY);
    }

    private ExecutionTarget.ScreenshotSpec shotSpec(int hostPort) {
        return new ExecutionTarget.ScreenshotSpec("orq_preview_local", 8000, hostPort,
                "imagem-ignorada-no-local", 1280, 800, 5000, 2 * 1024 * 1024);
    }

    @Test
    void shotLocalUsaOBrowserDoHostContraOLoopbackEDevolveOsBytes() throws Exception {
        // O LOCAL e dev-only best-effort: aqui um "browser" falso (escreve um PNG no arquivo do
        // --screenshot= e sai 0) prova a FIACAO do seam — resolver o binario, spawn, ler o arquivo,
        // classificar — sem depender de um Chromium real na maquina de teste/CI.
        Path fakeBrowser = Files.createTempFile("fake-chromium", ".sh");
        Files.writeString(fakeBrowser, "#!/bin/sh\n"
                + "for a in \"$@\"; do\n"
                + "  case \"$a\" in --screenshot=*) printf 'PNG-fake-shot-bytes' > \"${a#--screenshot=}\";; esac\n"
                + "done\n");
        assertThat(fakeBrowser.toFile().setExecutable(true)).isTrue();
        System.setProperty(LocalExecutionTarget.LOCAL_BROWSER_PROPERTY, fakeBrowser.toString());

        ExecutionTarget.ThumbnailCapture shot = target.screenshotServer(shotSpec(55001));

        assertThat(shot.captured()).as(shot.error()).isTrue();
        assertThat(new String(shot.bytes())).isEqualTo("PNG-fake-shot-bytes");
    }

    @Test
    void shotLocalSemBrowserNoHostERenderFailedBestEffortNuncaExcecao() {
        // Ressalva honesta da 0027 §B4: browser ausente no host -> RENDER_FAILED best-effort (o
        // capturador trata; a subida do servidor fica intocada). Nunca lanca.
        System.setProperty(LocalExecutionTarget.LOCAL_BROWSER_PROPERTY, "/nao/existe/chromium-xyz");

        ExecutionTarget.ThumbnailCapture shot = target.screenshotServer(shotSpec(55001));

        assertThat(shot.captured()).isFalse();
        assertThat(shot.outcome()).isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.RENDER_FAILED);
    }

    @Test
    void startSobeOProcessoEEleApareceNoListComAPortaDoManifest() throws Exception {
        Path workDir = Files.createTempDirectory("orq-preview-local");
        ExecutionTarget.PreviewServerSpec spec =
                spec("orq_preview_l1", workDir, "sleep 30", 3000);

        ExecutionTarget.PreviewServerStart start = target.startPreviewServer(spec);

        assertThat(start.failed()).as(start.error()).isFalse();
        assertThat(start.hostPort()).isEqualTo(3000);
        assertThat(target.listPreviewServers()).anySatisfy(vivo -> {
            assertThat(vivo.serverName()).isEqualTo("orq_preview_l1");
            assertThat(vivo.hostPort()).isEqualTo(3000);
            assertThat(vivo.companyId()).isEqualTo(spec.companyId());
            assertThat(vivo.issueId()).isEqualTo(spec.issueId());
        });
    }

    @Test
    void stopMataOProcessoEEIdempotente() throws Exception {
        Path workDir = Files.createTempDirectory("orq-preview-local");
        target.startPreviewServer(spec("orq_preview_l2", workDir, "sleep 30", 3000));

        ExecutionTarget.PreviewServerStop stop = target.stopPreviewServer("orq_preview_l2");

        assertThat(stop.failed()).isFalse();
        assertThat(target.listPreviewServers())
                .noneMatch(vivo -> vivo.serverName().equals("orq_preview_l2"));
        assertThat(target.stopPreviewServer("orq_preview_l2").failed())
                .as("parar o que nao existe e OK (teardown best-effort)")
                .isFalse();
    }

    @Test
    void processoQueMorreSomeDoListMasOTailAindaConta() throws Exception {
        // A materia-prima da falha legivel: o comando morreu ao subir -> o modulo le o tail
        // ANTES do stop (o registro nao pode sumir junto com o processo).
        Path workDir = Files.createTempDirectory("orq-preview-local");
        target.startPreviewServer(spec("orq_preview_l3", workDir,
                "echo porta ocupada: bind failed; exit 3", 3000));

        long prazo = System.currentTimeMillis() + 5_000;
        while (target.listPreviewServers().stream()
                .anyMatch(vivo -> vivo.serverName().equals("orq_preview_l3"))) {
            assertThat(System.currentTimeMillis()).as("processo deveria ter morrido").isLessThan(prazo);
            Thread.sleep(100);
        }

        assertThat(target.previewServerLogTail("orq_preview_l3", 20))
                .contains("porta ocupada: bind failed");
    }

    @Test
    void startComNomeJaVivoEFalhaLegivelComoNoDaemon() throws Exception {
        // Espelho do conflito de nome do docker run: a idempotencia (reusar o vivo) e decisao do
        // MODULO, nao do alvo — o alvo recusa legivel.
        Path workDir = Files.createTempDirectory("orq-preview-local");
        target.startPreviewServer(spec("orq_preview_l4", workDir, "sleep 30", 3000));

        ExecutionTarget.PreviewServerStart segundo =
                target.startPreviewServer(spec("orq_preview_l4", workDir, "sleep 30", 3000));

        assertThat(segundo.failed()).isTrue();
        assertThat(segundo.error()).contains("orq_preview_l4");
    }
}
