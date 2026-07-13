package com.orquestrador.skills.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KnowAllMcpMaterializer (F-AT2): grava o servidor MCP-stdio do KnowAll (script Node único, motor-
 * agnóstico) em {@code .orq/mcp/knowall-server.js} — irmão do HookMaterializer. Idempotente. As duas
 * tools se anunciam read-only (requisito do gate do codex, laudo R12-B) e o script é sintaticamente
 * válido ({@code node --check} quando Node está no PATH — sempre nos motores, que são pacotes npm).
 */
class KnowAllMcpMaterializerTest {

    private final KnowAllMcpMaterializer materializer = new KnowAllMcpMaterializer();

    @TempDir
    Path workDir;

    @Test
    void materializaOServidorNoOrqMcp() throws IOException {
        materializer.materialize(workDir);

        Path script = workDir.resolve(".orq/mcp/knowall-server.js");
        assertThat(Files.exists(script)).isTrue();
        String content = Files.readString(script);
        assertThat(content).isEqualTo(KnowAllMcpMaterializer.SERVER_SCRIPT);
        // As duas tools + o requisito readOnlyHint (o que o codex prova no headless, laudo R12-B).
        assertThat(content).contains("knowall_search").contains("knowall_fetch_body")
                .contains("readOnlyHint: true")
                .contains("x-orq-mcp-token");
    }

    @Test
    void idempotenteReescreveOScript() throws IOException {
        materializer.materialize(workDir);
        materializer.materialize(workDir);

        assertThat(Files.readString(workDir.resolve(".orq/mcp/knowall-server.js")))
                .isEqualTo(KnowAllMcpMaterializer.SERVER_SCRIPT);
    }

    @Test
    void oScriptMaterializadoEhSintaticamenteValido() throws IOException, InterruptedException {
        materializer.materialize(workDir);
        Path script = workDir.resolve(".orq/mcp/knowall-server.js");

        ProcessBuilder check = new ProcessBuilder("node", "--check", script.toString());
        check.redirectErrorStream(true);
        Process process;
        try {
            process = check.start();
        } catch (IOException nodeAusente) {
            Assumptions.abort("node não está no PATH deste ambiente — pulando o node --check");
            return;
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("node --check falhou: %s", output).isZero();
    }
}
