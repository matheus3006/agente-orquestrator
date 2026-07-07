package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F.12 (ADR-0026): a coleta no alvo LOCAL é delete recursivo — com GUARD de path canonicalizado
 * sob o root (defesa contra {@code rm -rf} catastrófico, invariante TESTADO), listagem do mundo
 * por derivação da árvore e classificação REMOVED/ABSENT. Puro, sem Spring.
 */
class LocalExecutionTargetRemoveTest {

    private final LocalExecutionTarget target = new LocalExecutionTarget();

    private final UUID companyId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID issueId = UUID.randomUUID();

    @Test
    void removeODiretorioDoWorkspaceSobORoot(@TempDir Path root) throws Exception {
        WorkspaceResolver resolver = new WorkspaceResolver(root.toString());
        Path workspace = resolver.resolve(companyId, agentId, issueId);
        Files.writeString(workspace.resolve("app.py"), "print('viva')");

        ExecutionTarget.IssueWorkspaceRemoval outcome =
                target.removeIssueWorkspace(resolver, companyId, agentId, issueId);

        assertThat(outcome).isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.REMOVED);
        assertThat(Files.exists(workspace)).isFalse();
    }

    @Test
    void workspaceAusenteEhAbsentNaoFalha(@TempDir Path root) {
        WorkspaceResolver resolver = new WorkspaceResolver(root.toString());

        assertThat(target.removeIssueWorkspace(resolver, companyId, agentId, issueId))
                .isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.ABSENT);
    }

    @Test
    void guardRecusaCaminhoForaDoRootEOAlvoSobrevive(@TempDir Path base) throws Exception {
        Path root = Files.createDirectory(base.resolve("root"));
        Path foraDoRoot = Files.createDirectory(base.resolve("vitima"));
        Files.writeString(foraDoRoot.resolve("segredo.txt"), "nao me apague");

        assertThatThrownBy(() -> LocalExecutionTarget.deleteWorkspaceTree(root, foraDoRoot))
                .isInstanceOf(SecurityException.class);

        assertThat(Files.exists(foraDoRoot.resolve("segredo.txt")))
                .as("caminho fora do root jamais e deletado").isTrue();
    }

    @Test
    void listaOMundoLocalDerivandoDaArvoreExcluindoWorkspaceDeAgente(@TempDir Path root) {
        WorkspaceResolver resolver = new WorkspaceResolver(root.toString());
        UUID otherCompany = UUID.randomUUID();
        UUID otherAgent = UUID.randomUUID();
        UUID otherIssue = UUID.randomUUID();
        resolver.resolve(companyId, agentId, issueId);          // workspace por-Issue
        resolver.resolve(companyId, agentId, null);             // workspace de AGENTE (_agent)
        resolver.resolve(otherCompany, otherAgent, otherIssue); // outro workspace por-Issue

        assertThat(target.listIssueWorkspaces(resolver))
                .containsExactlyInAnyOrder(
                        resolver.pathFor(companyId, agentId, issueId).toString(),
                        resolver.pathFor(otherCompany, otherAgent, otherIssue).toString())
                .doesNotContain(resolver.pathFor(companyId, agentId, null).toString());
    }
}
