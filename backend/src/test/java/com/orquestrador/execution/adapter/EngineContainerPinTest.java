package com.orquestrador.execution.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O elo que impede o pino de dessincronizar (F.8, ADR-0016): a versão do CLI que o
 * {@link EngineContainer} espera rodar É a versão que o Dockerfile instala. Se um lado mudar sem
 * o outro, este teste quebra — a defesa contra o {@code --bare} depende do pino valer de verdade.
 * (O surefire roda com cwd = backend/, então infra/ está um nível acima.)
 */
class EngineContainerPinTest {

    private static final Path ENGINES_DIR = Path.of("..", "infra", "engines");

    @Test
    void dockerfileDoClaudeInstalaExatamenteAVersaoQueOAdapterEspera() throws IOException {
        String dockerfile = Files.readString(ENGINES_DIR.resolve("claude-code").resolve("Dockerfile"));

        assertThat(dockerfile)
                .contains("ARG CLAUDE_CODE_VERSION=" + EngineContainer.CLAUDE_CODE_CLI_VERSION)
                .contains("@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}");
    }

    @Test
    void dockerfileDoCodexInstalaExatamenteAVersaoQueOAdapterEspera() throws IOException {
        String dockerfile = Files.readString(ENGINES_DIR.resolve("codex").resolve("Dockerfile"));

        assertThat(dockerfile)
                .contains("ARG CODEX_VERSION=" + EngineContainer.CODEX_CLI_VERSION)
                .contains("@openai/codex@${CODEX_VERSION}");
    }

    @Test
    void dockerfileDoCodexForcaCredencialEmArquivoNaoKeyring() throws IOException {
        // Refinamento obrigatório (deep-research): sem cli_auth_credentials_store="file" o codex
        // pode guardar a credencial em keyring — que não viaja no volume montado.
        String dockerfile = Files.readString(ENGINES_DIR.resolve("codex").resolve("Dockerfile"));

        assertThat(dockerfile).contains("cli_auth_credentials_store = \"file\"");
    }

    @Test
    void dockerfileDoClaudeDesligaOAutoUpdateParaOPinoValer() throws IOException {
        // Auto-update dentro do volume-home desatualizaria o CLI por baixo do pino.
        String dockerfile = Files.readString(ENGINES_DIR.resolve("claude-code").resolve("Dockerfile"));

        assertThat(dockerfile).contains("DISABLE_AUTOUPDATER=1");
    }
}
