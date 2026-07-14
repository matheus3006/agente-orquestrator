package com.orquestrador.skills.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * O PERFIL por PONTA (F-C3, C10), provado no script REAL: {@code tools/list} do servidor rodando
     * em node. A ponta do EXECUTOR anuncia os dois trilhos (skill + memória); a do CEO anuncia SÓ a
     * memória — o corpo de skill do KnowAll segue exclusivo do executor (F-AT2/D6, "personas FORA").
     * Todas {@code readOnlyHint:true}: é verdade (leitura pura) e é o que satisfaz o gate do codex.
     */
    @Test
    void oPerfilDaPontaFiltraAsToolsAnunciadas() throws IOException, InterruptedException {
        materializer.materialize(workDir);
        Path script = workDir.resolve(".orq/mcp/knowall-server.js");

        assertThat(toolsAnunciadas(script, "executor"))
                .containsExactlyInAnyOrder("knowall_search", "knowall_fetch_body",
                        "memory_search", "memory_fetch");
        assertThat(toolsAnunciadas(script, "ceo"))
                .as("a persona não navega o catálogo de skills mid-turno")
                .containsExactlyInAnyOrder("memory_search", "memory_fetch");
    }

    /** Fala MCP de verdade com o script (JSON-RPC por stdio) e devolve os nomes das tools anunciadas. */
    private List<String> toolsAnunciadas(Path script, String profile) throws IOException, InterruptedException {
        ProcessBuilder run = new ProcessBuilder("node", script.toString(),
                "http://127.0.0.1:1/", "token-de-teste", profile);
        run.redirectErrorStream(true);
        Process process;
        try {
            process = run.start();
        } catch (IOException nodeAusente) {
            Assumptions.abort("node não está no PATH deste ambiente — pulando o tools/list real");
            return List.of();
        }
        process.getOutputStream().write(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();
        String saida = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        assertThat(saida).as("o servidor respondeu o tools/list").contains("\"tools\"");
        assertThat(saida).as("readOnlyHint em toda tool (gate do codex)").doesNotContain("readOnlyHint\":false");
        List<String> nomes = new ArrayList<>();
        Matcher nome = Pattern.compile("\"name\":\"([a-z_]+)\"").matcher(saida);
        while (nome.find()) {
            nomes.add(nome.group(1));
        }
        return nomes;
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
