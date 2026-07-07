package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.9 (ADR-0021): a leitura de workspace no LOCAL e um read no diretorio real — com real-path
 * check anti-symlink: o agente escreve no workspace, e um symlink que aponta para FORA dele
 * (ex.: ~/.ssh) nunca pode virar conteudo capturado. No DOCKER o escape e inocuo por construcao
 * (o container so ve o volume); no LOCAL o guard e este teste.
 */
class LocalExecutionTargetReadTest {

    private static final long CAP = 2 * 1024 * 1024;

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    private ExecutionTarget.Spec specFor(Path workDir) {
        return new ExecutionTarget.Spec(workDir, List.of(), Map.of(), null, null, List.of());
    }

    @Test
    void leOArquivoDoContratoDeLugar() throws Exception {
        Path contrato = workspace.resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        Files.writeString(contrato, "<html>oi</html>");

        ExecutionTarget.WorkspaceFileRead read = new LocalExecutionTarget()
                .readWorkspaceFile(specFor(workspace), ".orq/preview/index.html", CAP);

        assertThat(read.found()).isTrue();
        assertThat(new String(read.bytes(), StandardCharsets.UTF_8)).isEqualTo("<html>oi</html>");
    }

    @Test
    void arquivoAusenteERunNormalNaoFalha() {
        ExecutionTarget.WorkspaceFileRead read = new LocalExecutionTarget()
                .readWorkspaceFile(specFor(workspace), ".orq/preview/index.html", CAP);

        assertThat(read.absent()).isTrue();
        assertThat(read.error()).isNull();
    }

    @Test
    void symlinkQueEscapaDoWorkspaceERejeitadoComFalhaLegivel() throws Exception {
        Path segredo = outside.resolve("segredo.txt");
        Files.writeString(segredo, "conteudo-que-nao-pode-vazar");
        Path contrato = workspace.resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        Files.createSymbolicLink(contrato, segredo);

        ExecutionTarget.WorkspaceFileRead read = new LocalExecutionTarget()
                .readWorkspaceFile(specFor(workspace), ".orq/preview/index.html", CAP);

        assertThat(read.failed()).isTrue();
        assertThat(read.error()).contains("escapa do workspace");
        assertThat(read.bytes()).isNull();
    }

    @Test
    void fifoNoContratoNaoTravaALeitura() throws Exception {
        // Um FIFO plantado no lugar do contrato bloquearia Files.readAllBytes para sempre
        // (open sem escritor) — e a captura sincrona penduraria o settle e o lock da Issue.
        // Arquivo nao-regular = falha legivel ANTES de qualquer byte ser lido.
        Path contrato = workspace.resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        new ProcessBuilder("mkfifo", contrato.toString()).start().waitFor();

        ExecutionTarget.WorkspaceFileRead read = new LocalExecutionTarget()
                .readWorkspaceFile(specFor(workspace), ".orq/preview/index.html", CAP);

        assertThat(read.failed()).isTrue();
        assertThat(read.error()).contains("regular");
    }

    @Test
    void arquivoAcimaDoCapFalhaLegivelNuncaTrunca() throws Exception {
        Path contrato = workspace.resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        Files.writeString(contrato, "x".repeat(64));

        ExecutionTarget.WorkspaceFileRead read = new LocalExecutionTarget()
                .readWorkspaceFile(specFor(workspace), ".orq/preview/index.html", 63);

        assertThat(read.failed()).isTrue();
        assertThat(read.error()).contains("cap");
        assertThat(read.bytes()).isNull();
    }
}
