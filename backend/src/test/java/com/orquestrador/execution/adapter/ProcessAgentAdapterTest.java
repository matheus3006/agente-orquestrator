package com.orquestrador.execution.adapter;

import com.orquestrador.execution.adapter.AgentAdapter.Context;
import com.orquestrador.execution.adapter.AgentAdapter.Result;
import com.orquestrador.execution.runtime.ExecutionTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O arquetipo process no seam de F.8 (ADR-0016): shell puro, sem plano de assinatura — o Spec
 * carrega a imagem do {@link EngineContainer#plainShell()} e NENHUM volume de credencial.
 */
class ProcessAgentAdapterTest {

    private final ProcessAgentAdapter adapter = new ProcessAgentAdapter();

    @Test
    void rodaShellPuroSemCredencialDePlanoAlgum() {
        FakeTarget target = new FakeTarget();

        Result result = adapter.execute(ctx(target, "echo oi"), (type, detail) -> {
        });

        assertThat(result.succeeded()).isTrue();
        assertThat(target.spec.command()).containsExactly("sh", "-c", "echo oi");
        assertThat(target.spec.image()).isEqualTo(EngineContainer.plainShell().image());
        assertThat(target.spec.credentialMounts()).isEmpty();
    }

    private static Context ctx(ExecutionTarget target, String command) {
        return new Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Path.of("/tmp"), command, Map.of(), target, "vol", null, null, null);
    }

    private static final class FakeTarget implements ExecutionTarget {
        private Spec spec;

        @Override
        public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
            return WorkspaceFileRead.ABSENT;
        }

        @Override
        public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
            return WorkspaceFilesWrite.OK;
        }

        @Override
        public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
            return PreviewServerStart.failure("fake sem servidor de preview");
        }

        @Override
        public PreviewServerStop stopPreviewServer(String serverName) {
            return PreviewServerStop.OK;
        }

        @Override
        public List<PreviewServerLive> listPreviewServers() {
            return List.of();
        }

        @Override
        public String previewServerLogTail(String serverName, int maxLines) {
            return "";
        }

        @Override
        public Type type() {
            return Type.LOCAL;
        }

        @Override
        public Result run(Spec spec, Consumer<String> onStdoutLine) {
            this.spec = spec;
            List.of("oi").forEach(onStdoutLine);
            return new Result(0, null);
        }
    }
}
