package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.12 (ADR-0026): o resolver é o dono único da NOMEAÇÃO — quem batiza o workspace por-Issue.
 * O `WorkspaceReaper` deriva o ref esperado de cada Issue por AQUI e intersecta com o mundo
 * (world-driven, zero parsing). Estes casos travam as derivações que o reaper depende:
 * caminho puro (sem criar diretório), ref por alvo e o reconhecimento ESTRUTURAL do formato
 * de volume por-Issue (a denylist de creds/pgdata/agente).
 */
class WorkspaceResolverTest {

    private final UUID companyId = UUID.fromString("142f1ac5-0000-4000-8000-000000000001");
    private final UUID agentId = UUID.fromString("deadbeef-0000-4000-8000-000000000002");
    private final UUID issueId = UUID.fromString("67aa95e5-0000-4000-8000-000000000003");

    private WorkspaceResolver resolverRootedAt(Path root) {
        return new WorkspaceResolver(root.toString());
    }

    @Test
    void pathForNaoCriaDiretorio(@org.junit.jupiter.api.io.TempDir Path root) {
        // O sweep NUNCA materializa: pathFor é puro (ao contrário de resolve, que faz mkdir).
        WorkspaceResolver resolver = resolverRootedAt(root);

        Path path = resolver.pathFor(companyId, agentId, issueId);

        assertThat(path).isEqualTo(root.resolve(companyId.toString())
                .resolve(agentId.toString()).resolve(issueId.toString()));
        assertThat(Files.exists(path)).as("pathFor nao deve criar o diretorio").isFalse();
    }

    @Test
    void refPorAlvoCasaAVocabularioDeCadaMundo(@org.junit.jupiter.api.io.TempDir Path root) {
        // DOCKER: o ref é o nome de volume (UUID encurtado). LOCAL: o ref é o caminho absoluto.
        // O reaper compara o mundo listado com refFor — os dois têm de sair IDÊNTICOS por alvo.
        WorkspaceResolver resolver = resolverRootedAt(root);

        assertThat(resolver.refFor(ExecutionTarget.Type.DOCKER, companyId, agentId, issueId))
                .isEqualTo(resolver.volumeName(companyId, agentId, issueId))
                .isEqualTo("orq_142f1ac5_deadbeef_67aa95e5");
        assertThat(resolver.refFor(ExecutionTarget.Type.LOCAL, companyId, agentId, issueId))
                .isEqualTo(resolver.pathFor(companyId, agentId, issueId).toString());
    }

    @Test
    void reconheceOFormatoDeVolumePorIssueEExcluiCredsEAgente(@org.junit.jupiter.api.io.TempDir Path root) {
        // Denylist ESTRUTURAL (não lista mágica): só `orq_<8hex>_<8hex>_<8hex>` é workspace por-Issue.
        WorkspaceResolver resolver = resolverRootedAt(root);

        assertThat(resolver.isIssueVolumeName("orq_142f1ac5_deadbeef_67aa95e5")).isTrue();
        assertThat(resolver.isIssueVolumeName("orq_deadbeef_cafebabe_12345678")).isTrue();

        // fora por construção: creds, pgdata, workspace de agente (2 segmentos) e não-orq
        assertThat(resolver.isIssueVolumeName("orq_claude_creds")).isFalse();
        assertThat(resolver.isIssueVolumeName("orq_codex_creds")).isFalse();
        assertThat(resolver.isIssueVolumeName("pgdata")).isFalse();
        assertThat(resolver.isIssueVolumeName("orq_142f1ac5_deadbeef")).isFalse();
        assertThat(resolver.isIssueVolumeName("6bea9500b758c0ffee")).isFalse();
        // ruído: maiúsculas / segmento fora de 8 chars não casam
        assertThat(resolver.isIssueVolumeName("orq_142F1AC5_deadbeef_67aa95e5")).isFalse();
        assertThat(resolver.isIssueVolumeName("orq_142f1ac_deadbeef_67aa95e5")).isFalse();
    }
}
