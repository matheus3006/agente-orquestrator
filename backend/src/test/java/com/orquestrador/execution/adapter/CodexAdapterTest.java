package com.orquestrador.execution.adapter;

import com.orquestrador.execution.adapter.AgentAdapter.Context;
import com.orquestrador.execution.adapter.AgentAdapter.CostEmission;
import com.orquestrador.execution.adapter.AgentAdapter.Result;
import com.orquestrador.execution.adapter.AgentAdapter.StepSink;
import com.orquestrador.execution.runtime.ExecutionTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fatia D (ADR-0014): o {@code CodexAdapter} reusa o molde da C (argv + parser + target) com a gramática do
 * `codex` e {@code provider="openai"}. Testado com target FALSO (o CLI real não roda no teste): argv, steps,
 * session e CostEvent. O Codex não emite {@code model} no stream desta fixture → o CostEmission cai no model
 * pedido no motor (prova o fallback).
 */
class CodexAdapterTest {

    private static final List<String> STREAM = List.of(
            "{\"type\":\"thread.started\",\"thread_id\":\"th_123\"}",
            "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Pronto.\"}}",
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200,\"cached_input_tokens\":1000,"
                    + "\"output_tokens\":50}}");

    private final CodexAdapter adapter = new CodexAdapter(6, 3);

    @Test
    void tipoEhCodex() {
        assertThat(adapter.type()).isEqualTo("codex");
    }

    @Test
    void executaMontaArgvParseiaStreamEEmiteCostEventOpenai() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, STREAM);

        Result result = adapter.execute(ctx(target, "faça X", "gpt-5.5", "high"), sink);

        assertThat(target.spec.command()).containsExactly(
                "codex", "exec", "--json", "--model", "gpt-5.5", "-c", "model_reasoning_effort=high",
                "-c", "agents.max_threads=6", "-c", "agents.max_depth=3",
                "--skip-git-repo-check", "-s", "workspace-write", "faça X");
        // F.8 (ADR-0016): container próprio — imagem pinada do codex + SÓ a credencial do plano
        // Codex (jamais a do Claude; a prova de interseção vazia vive no EngineContainerTest).
        assertThat(target.spec.image()).isEqualTo(EngineContainer.codex().image());
        assertThat(target.spec.credentialMounts())
                .isEqualTo(EngineContainer.codex().credentialMounts());
        // A mensagem do agente sobe ao nível RESULT (last-wins — a correção do reply do codex, ADR-0030).
        assertThat(sink.types).containsExactly("THREAD.STARTED", "RESULT", "TURN.COMPLETED");
        assertThat(result.succeeded()).isTrue();
        assertThat(result.session()).isEqualTo("th_123");
        assertThat(result.costEvents()).hasSize(1);
        CostEmission cost = result.costEvents().get(0);
        assertThat(cost.provider()).isEqualTo("openai");
        assertThat(cost.model()).isEqualTo("gpt-5.5"); // fallback: o stream não trouxe model
        assertThat(cost.tokensInput()).isEqualTo(1200);
        assertThat(cost.tokensOutput()).isEqualTo(50);
    }

    @Test
    void posturaDoTargetDecideOSandboxNoArgv() {
        // F.11 (ADR-0025): a politica de sandbox mora no ALVO (posture), nao na gramatica — o
        // adapter fia context.target().posture() ao CodexArgv. Sandbox externo => bypass, sem -s.
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, STREAM, ExecutionTarget.Type.DOCKER,
                ExecutionTarget.IsolationPosture.EXTERNALLY_SANDBOXED);

        adapter.execute(ctx(target, "faça X", null, null), sink);

        assertThat(target.spec.command())
                .containsExactly("codex", "exec", "--json",
                        "-c", "agents.max_threads=6", "-c", "agents.max_depth=3",
                        "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox", "faça X");
    }

    @Test
    void oTetoDeSubagenteDoD6ChegaAoArgvEmTodoRun() {
        // D6 (ADR-0038): o teto não é opcional — o adapter o embarca em TODO run (CEO e executor).
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, STREAM);

        adapter.execute(ctx(target, "faça X", "gpt-5.5", "high"), sink);

        assertThat(target.spec.command())
                .containsSubsequence("-c", "agents.max_threads=6", "-c", "agents.max_depth=3");
    }

    @Test
    void falhaCarregaOsTokensJaGastos() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(2, STREAM);

        Result result = adapter.execute(ctx(target, "t", "gpt-5.5", null), sink);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("exit 2");
        assertThat(result.costEvents()).hasSize(1);
        assertThat(result.costEvents().get(0).tokensOutput()).isEqualTo(50);
    }

    @Test
    void credencialQuebradaNoDockerViraFalhaLegivelComVolumeEScriptDeSetup() {
        // Peça 4 do ADR-0016. No Codex o aviso pode vir DENTRO da gramática (evento error do
        // JSONL) — o parser fareja a message do error além das linhas em texto puro.
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(1,
                List.of("{\"type\":\"error\",\"message\":\"401 Unauthorized: token expired, run codex login\"}"),
                ExecutionTarget.Type.DOCKER);

        Result result = adapter.execute(ctx(target, "t", null, null), sink);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error())
                .contains("orq_codex_creds")
                .contains("setup-codex.sh")
                .contains("401 Unauthorized");
    }

    @Test
    void falhaSemSinalDeCredencialMantemOErroGenerico() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(2, List.of("segfault"), ExecutionTarget.Type.DOCKER);

        Result result = adapter.execute(ctx(target, "t", null, null), sink);

        assertThat(result.error()).contains("exit 2").doesNotContain("orq_codex_creds");
    }

    @Test
    void semComandoEhNoOpVerdeSemChamarOTarget() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, List.of());

        Result result = adapter.execute(ctx(target, null, "gpt-5.5", "high"), sink);

        assertThat(result.succeeded()).isTrue();
        assertThat(target.ran).isFalse();
        assertThat(result.costEvents()).isEmpty();
    }

    private static Context ctx(ExecutionTarget target, String command, String model, String effort) {
        return new Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Path.of("/tmp"), command, Map.of(), target, "vol", null, model, effort);
    }

    private static final class RecordingSink implements StepSink {
        final List<String> types = new ArrayList<>();

        @Override
        public void emit(String type, String detail) {
            types.add(type);
        }
    }

    private static final class FakeTarget implements ExecutionTarget {
        private final int exitCode;

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
        private final List<String> lines;
        private final Type type;
        private final IsolationPosture posture;
        private Spec spec;
        private boolean ran;

        FakeTarget(int exitCode, List<String> lines) {
            this(exitCode, lines, Type.LOCAL);
        }

        FakeTarget(int exitCode, List<String> lines, Type type) {
            this(exitCode, lines, type, IsolationPosture.HOST);
        }

        FakeTarget(int exitCode, List<String> lines, Type type, IsolationPosture posture) {
            this.exitCode = exitCode;
            this.lines = lines;
            this.type = type;
            this.posture = posture;
        }

        @Override
        public Type type() {
            return type;
        }

        @Override
        public IsolationPosture posture() {
            return posture;
        }

        @Override
        public Result run(Spec spec, Consumer<String> onStdoutLine) {
            this.spec = spec;
            this.ran = true;
            lines.forEach(onStdoutLine);
            return new Result(exitCode, exitCode == 0 ? null : "boom");
        }
    }
}
