package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emenda R29-A (ADR-0021): a listagem rasa do diretorio-contrato e o que permite capturar o
 * entregavel de pesquisa {@code *.md} de nome livre. Espelho das guardas do leitor: so BASENAME
 * de arquivo REGULAR — subdiretorio e symlink ficam de fora (symlink que interessasse seria
 * recusado pelo leitor depois; aqui ele nem entra na contagem de ambiguidade).
 */
class LocalExecutionTargetListTest {

    @TempDir
    Path workspace;

    @TempDir
    Path outside;

    private ExecutionTarget.Spec specFor(Path workDir) {
        return new ExecutionTarget.Spec(workDir, List.of(), Map.of(), null, null, List.of());
    }

    @Test
    void listaSoBasenamesDeArquivosRegularesOrdenados() throws Exception {
        Path dir = workspace.resolve(".orq/preview");
        Files.createDirectories(dir.resolve("subdir"));
        Files.writeString(dir.resolve("b-digest.md"), "# b");
        Files.writeString(dir.resolve("a-pricing.md"), "# a");
        Files.writeString(dir.resolve("dados.csv"), "x,y");
        Files.createSymbolicLink(dir.resolve("fora.md"), outside.resolve("segredo.md"));

        ExecutionTarget.WorkspaceDirListing listagem = new LocalExecutionTarget()
                .listWorkspaceDir(specFor(workspace), ".orq/preview");

        assertThat(listagem.failed()).isFalse();
        assertThat(listagem.filenames()).containsExactly("a-pricing.md", "b-digest.md", "dados.csv");
    }

    @Test
    void diretorioAusenteEListagemVaziaNaoFalha() {
        ExecutionTarget.WorkspaceDirListing listagem = new LocalExecutionTarget()
                .listWorkspaceDir(specFor(workspace), ".orq/preview");

        assertThat(listagem.failed()).isFalse();
        assertThat(listagem.filenames()).isEmpty();
    }

    @Test
    void diretorioSymlinkParaForaDoWorkspaceNaoLista() throws Exception {
        // O proprio .orq/preview como symlink para fora: isDirectory(NOFOLLOW) ja recusa o
        // symlink na porta — vira listagem vazia, nunca uma varredura fora do workspace.
        Files.createDirectories(workspace.resolve(".orq"));
        Files.writeString(outside.resolve("vazou.md"), "# segredo");
        Files.createSymbolicLink(workspace.resolve(".orq/preview"), outside);

        ExecutionTarget.WorkspaceDirListing listagem = new LocalExecutionTarget()
                .listWorkspaceDir(specFor(workspace), ".orq/preview");

        assertThat(listagem.failed()).isFalse();
        assertThat(listagem.filenames()).isEmpty();
    }
}
