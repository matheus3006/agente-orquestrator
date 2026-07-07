package com.orquestrador.preview.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O elo que impede o pino da imagem-shot de dessincronizar (F.13/ADR-0027) — irmao do
 * {@code EngineContainerPinTest}: o release do Alpine que o {@link PreviewThumbnailCapturer} espera
 * (a tag {@code orq-preview-shot:alpine<r>}) E o que o Dockerfile PINA no {@code FROM alpine:<r>}. Se
 * um lado mudar sem o outro, este teste quebra — a defesa contra a tag flutuante ({@code --bare})
 * depende do pino valer de verdade. (O surefire roda com cwd = backend/, infra/ esta um nivel acima.)
 */
class PreviewShotImagePinTest {

    private static final Path SHOT_DIR = Path.of("..", "infra", "preview-shot");

    @Test
    void dockerfilePinaExatamenteOReleaseDoAlpineQueOCapturadorEspera() throws IOException {
        String dockerfile = Files.readString(SHOT_DIR.resolve("Dockerfile"));

        assertThat(dockerfile)
                .contains("FROM alpine:" + PreviewThumbnailCapturer.SHOT_ALPINE_RELEASE);
        // NUNCA uma tag flutuante — furaria a reprodutibilidade.
        assertThat(dockerfile)
                .doesNotContain("FROM alpine:latest")
                .doesNotContain("FROM alpine:edge");
    }

    @Test
    void dockerfileUsaChromiumCOMPLETOResolveOBinarioEInstalaFonte() throws IOException {
        // O smoke do argv (ADR-0027 §C.2/§E) reprovou o headless-shell (CDP-only, sem one-shot
        // --screenshot). Estas 3 linhas fazem o `chromium … --screenshot … && cat` do seam funcionar:
        String dockerfile = Files.readString(SHOT_DIR.resolve("Dockerfile"));

        // 1. chromium COMPLETO do Alpine (o pacote `chromium`) — implementa o one-shot --screenshot
        assertThat(dockerfile).contains("apk add").contains("chromium");
        // 2. o binario do Alpine e `chromium-browser` — symlink `chromium` para o argv resolver
        assertThat(dockerfile).contains("ln -sf /usr/bin/chromium-browser /usr/local/bin/chromium");
        // 3. sem fonte o texto vira tofu -> instala uma fonte Latina
        assertThat(dockerfile).contains("font-liberation");
    }
}
