package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No LOCAL o workDir E o workspace do Run: os materializers ja escreveram no lugar — o escritor
 * e IDENTIDADE (OK sem tocar byte). O comportamento real de escrita e do DOCKER (a arvore
 * atravessa para o volume nomeado), coberto pelos testes de argv em DockerExecutionTargetArgsTest.
 */
class LocalExecutionTargetWriteTest {

    @TempDir
    Path workspace;

    @Test
    void escreverNoLocalEIdentidadeOWorkDirJaEOWorkspace() throws Exception {
        Path skillMd = workspace.resolve(".claude/skills/mat-tdd/SKILL.md");
        Files.createDirectories(skillMd.getParent());
        Files.writeString(skillMd, "# TDD");

        ExecutionTarget.WorkspaceFilesWrite write = new LocalExecutionTarget().writeWorkspaceFiles(
                new ExecutionTarget.Spec(workspace, List.of(), Map.of(), null, null, List.of()),
                List.of(".claude/skills", ".claude/CLAUDE.md"));

        assertThat(write.failed()).isFalse();
        // Nada removido nem reescrito: replace-por-subtree e mecanica do DOCKER; aqui a arvore
        // materializada JA esta no unico lugar que existe.
        assertThat(Files.readString(skillMd)).isEqualTo("# TDD");
    }
}
