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
 * MARCO do adapter rico (fatia C, ADR-0014): o {@code ClaudeCodeAdapter} costura o argv (ClaudeArgv) +
 * o parser (ClaudeStreamParser) + o ExecutionTarget. Testado com um target FALSO (o CLI real não roda no
 * teste): prova o argv, os steps semânticos, a session e o CostEvent (provider=anthropic, tokens reais).
 */
class ClaudeCodeAdapterTest {

    private static final List<String> STREAM = List.of(
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-abc\",\"model\":\"claude-opus-4-8\"}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Trabalhando.\"}]}}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\"}]}}",
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}",
            "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"Pronto.\",\"session_id\":\"sess-abc\","
                    + "\"usage\":{\"input_tokens\":120,\"cache_read_input_tokens\":3000,"
                    + "\"cache_creation_input_tokens\":0,\"output_tokens\":45}}");

    private final ClaudeCodeAdapter adapter = new ClaudeCodeAdapter("");

    @Test
    void tipoEhClaudeCode() {
        assertThat(adapter.type()).isEqualTo("claude-code");
    }

    @Test
    void executaMontaArgvParseiaStreamEEmiteCostEvent() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, STREAM);

        Result result = adapter.execute(ctx(target, "faça X", "opus", "ultracode"), sink);

        // argv: prompt como argv puro (sem shell) + ultracode → --settings (não --effort)
        assertThat(target.spec.command()).containsExactly(
                "claude", "-p", "faça X", "--output-format", "stream-json", "--verbose",
                "--permission-mode", "bypassPermissions",
                "--model", "opus", "--settings", "{\"ultracode\":true}");
        // F.8 (ADR-0016): o adapter monta o PRÓPRIO container — imagem pinada + SÓ a credencial
        // do plano Claude (o executor não conhece nada disso; o Context nem carrega imagem).
        assertThat(target.spec.image()).isEqualTo(EngineContainer.claudeCode().image());
        assertThat(target.spec.credentialMounts())
                .isEqualTo(EngineContainer.claudeCode().credentialMounts());
        // cada linha do stream virou um step semântico
        assertThat(sink.types).containsExactly("SYSTEM", "ASSISTANT", "ASSISTANT", "USER", "RESULT");
        // sucesso: session capturada + CostEvent com tokens REAIS e o model resolvido do stream
        assertThat(result.succeeded()).isTrue();
        assertThat(result.session()).isEqualTo("sess-abc");
        assertThat(result.costEvents()).hasSize(1);
        CostEmission cost = result.costEvents().get(0);
        assertThat(cost.provider()).isEqualTo("anthropic");
        assertThat(cost.model()).isEqualTo("claude-opus-4-8");
        assertThat(cost.tokensInput()).isEqualTo(3120);
        assertThat(cost.tokensOutput()).isEqualTo(45);
    }

    @Test
    void falhaCarregaOsTokensJaGastos() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(1, STREAM); // exit != 0 DEPOIS de já ter lido o usage

        Result result = adapter.execute(ctx(target, "t", "opus", "high"), sink);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).contains("exit 1");
        // tokens gastos antes de falhar contam — fail-soft carrega os costEvents (factory da fatia B)
        assertThat(result.costEvents()).hasSize(1);
        assertThat(result.costEvents().get(0).tokensOutput()).isEqualTo(45);
    }

    @Test
    void injetaOSetupTokenNoEnvSemPerderOEnvDoContexto() {
        // Alternativa de automação (ADR-0016): CLAUDE_CODE_OAUTH_TOKEN do `claude setup-token`
        // (~1 ano) via property — evita o login interativo no volume. O merge preserva o env
        // que o executor mandou (ORQ_PROMPT do turno).
        ClaudeCodeAdapter comToken = new ClaudeCodeAdapter("tok-de-1-ano");
        FakeTarget target = new FakeTarget(0, STREAM);

        comToken.execute(new Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                Path.of("/tmp"), "t", Map.of("ORQ_PROMPT", "oi"), target, "vol", null, null, null),
                new RecordingSink());

        assertThat(target.spec.env())
                .containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "tok-de-1-ano")
                .containsEntry("ORQ_PROMPT", "oi");
    }

    @Test
    void semSetupTokenConfiguradoOEnvNaoGanhaAChave() {
        FakeTarget target = new FakeTarget(0, STREAM);

        adapter.execute(ctx(target, "t", null, null), new RecordingSink());

        assertThat(target.spec.env()).doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
    }

    @Test
    void credencialQuebradaNoDockerViraFalhaLegivelComVolumeEScriptDeSetup() {
        // Peça 4 do ADR-0016: token expirado/ausente → falha LEGÍVEL (nunca silêncio). O aviso
        // do CLI chega em texto puro (FORA da gramática JSON — output do modelo vive dentro de
        // JSON, então não há falso-positivo) e o erro do Run aponta o remédio.
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(1, List.of("Invalid API key · Please run /login"),
                ExecutionTarget.Type.DOCKER);

        Result result = adapter.execute(ctx(target, "t", null, null), sink);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.error())
                .contains("orq_claude_creds")
                .contains("setup-claude.sh")
                .contains("Please run /login");
    }

    @Test
    void credencialQuebradaNoLocalApontaOLoginDoHost() {
        // No alvo LOCAL não há volume: o remédio é o login do próprio host.
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(1, List.of("Not logged in"), ExecutionTarget.Type.LOCAL);

        Result result = adapter.execute(ctx(target, "t", null, null), sink);

        assertThat(result.error()).contains("host").doesNotContain("orq_claude_creds");
    }

    @Test
    void falhaSemSinalDeCredencialMantemOErroGenerico() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(1, List.of("java.lang.OutOfMemoryError"),
                ExecutionTarget.Type.DOCKER);

        Result result = adapter.execute(ctx(target, "t", null, null), sink);

        assertThat(result.error()).contains("exit 1").doesNotContain("orq_claude_creds");
    }

    @Test
    void semComandoEhNoOpVerdeSemChamarOTarget() {
        RecordingSink sink = new RecordingSink();
        FakeTarget target = new FakeTarget(0, List.of());

        Result result = adapter.execute(ctx(target, null, "opus", "high"), sink);

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
        final List<String> details = new ArrayList<>();

        @Override
        public void emit(String type, String detail) {
            types.add(type);
            details.add(detail);
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
        private Spec spec;
        private boolean ran;

        FakeTarget(int exitCode, List<String> lines) {
            this(exitCode, lines, Type.LOCAL);
        }

        FakeTarget(int exitCode, List<String> lines, Type type) {
            this.exitCode = exitCode;
            this.lines = lines;
            this.type = type;
        }

        @Override
        public Type type() {
            return type;
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
