package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * O run do alvo LOCAL (F.11/ADR-0025): postura de isolamento e stdin do filho. O achado E3 da
 * prova: motor que le stdin (`codex exec`) TRAVAVA no pipe aberto default do ProcessBuilder —
 * o alvo redireciona de /dev/null (EOF imediato) para todo motor, presente e futuro.
 */
class LocalExecutionTargetRunTest {

    @TempDir
    Path workDir;

    @Test
    void posturaEhHostPeloDefaultFailSafeDaInterface() {
        // LOCAL roda direto no host (dev-only, ADR-0016) — o motor DEVE se auto-sandboxar.
        assertThat(new LocalExecutionTarget().posture())
                .isEqualTo(ExecutionTarget.IsolationPosture.HOST);
    }

    @Test
    void runNaoDeixaOFilhoPenduradoLendoStdin() {
        // `cat` sem argumento le stdin ate EOF: com o pipe default nunca termina (bug E3);
        // com /dev/null recebe EOF imediato e sai 0.
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                workDir, List.of("cat"), Map.of(), null, null, List.of());

        ExecutionTarget.Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new LocalExecutionTarget().run(spec, line -> {
                }));

        assertThat(result.exitCode()).isZero();
    }
}
