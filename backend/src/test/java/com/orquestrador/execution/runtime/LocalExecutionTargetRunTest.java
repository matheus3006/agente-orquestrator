package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                () -> new LocalExecutionTarget().run(spec, UUID.randomUUID(), line -> {
                }));

        assertThat(result.exitCode()).isZero();
    }

    /**
     * ADR-0050: o run que NUNCA termina estoura o prazo em vez de segurar a thread para sempre.
     *
     * <p>Sem isto o {@code sleep 3600} bloqueia o chamador indefinidamente — e NAO no
     * {@code waitFor}: o drain de stdout ({@code readLine}) e que nunca ve EOF, entao o
     * {@code waitFor} sequer e alcancado. Medido antes de escrever o conserto; e a razao de o
     * prazo ter de cercar a OPERACAO INTEIRA, nao so a espera final.
     */
    @Test
    void runQueNuncaTerminaEstouraOPrazoEmVezDeSegurarAThreadParaSempre() {
        LocalExecutionTarget target = new LocalExecutionTarget(
                PrazosDeExecucao.comPrazos(Duration.ofSeconds(30), Duration.ofSeconds(30),
                        Duration.ofSeconds(30), Duration.ofMillis(500)));
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                workDir, List.of("sh", "-c", "sleep 3600"), Map.of(), null, null, List.of());

        ExecutionTarget.Result result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> target.run(spec, UUID.randomUUID(), line -> { }));

        assertThat(result.exitCode()).as("run morto por prazo nao pode passar por saida limpa").isNotZero();
        assertThat(result.error()).contains("prazo");
    }

    /**
     * ADR-0050: o estouro de prazo ESCALA a derrubada — nao basta matar o processo do host.
     *
     * <p>Um {@code destroyForcibly} so-no-pai deixaria o filho da tool call vivo e orfao (o
     * requisito duro da sonda R6-B, ja provado para o interrupt manual). Um prazo que devolve a
     * thread mas deixa o trabalho rodando trocaria uma trava visivel por um vazamento invisivel.
     */
    @Test
    void estouroDePrazoMataTambemODescendenteDaTool() throws Exception {
        LocalExecutionTarget target = new LocalExecutionTarget(
                PrazosDeExecucao.comPrazos(Duration.ofSeconds(30), Duration.ofSeconds(30),
                        Duration.ofSeconds(30), Duration.ofSeconds(1)));
        Path pidFile = workDir.resolve("childpid");
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(workDir,
                List.of("sh", "-c", "cat /dev/zero > /dev/null & echo $! > childpid; wait"),
                Map.of(), null, null, List.of());

        CompletableFuture<ExecutionTarget.Result> execucao = CompletableFuture.supplyAsync(
                () -> target.run(spec, UUID.randomUUID(), line -> { }));

        long childPid = aguardarPid(pidFile, Duration.ofSeconds(5));
        ExecutionTarget.Result resultado = execucao.get(15, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(resultado.error()).contains("prazo");
        aguardarMorte(childPid, Duration.ofSeconds(5));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                .as("o filho da tool nao pode sobreviver orfao ao estouro de prazo")
                .isFalse();
    }

    /**
     * F-N4 (ADR-0040, D4): a integracao real run()->registro->interrupt()->kill, ponta a ponta
     * no alvo LOCAL. O filho (cat lendo /dev/zero, o "tool call" demorado da sonda R6-B) roda
     * dentro do `sh -c` que o run() de fato inicia — matar por runId tem que alcancar os dois.
     */
    @Test
    void interruptMataUmRunEmAndamentoEODescendenteDaTool() throws Exception {
        LocalExecutionTarget target = new LocalExecutionTarget();
        UUID runId = UUID.randomUUID();
        Path pidFile = workDir.resolve("childpid");
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(workDir,
                List.of("sh", "-c", "cat /dev/zero > /dev/null & echo $! > childpid; wait"),
                Map.of(), null, null, List.of());

        CompletableFuture<ExecutionTarget.Result> execucao = CompletableFuture.supplyAsync(
                () -> target.run(spec, runId, line -> { }));

        long childPid = aguardarPid(pidFile, Duration.ofSeconds(5));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isTrue();

        assertThat(target.interrupt(runId)).isTrue();

        ExecutionTarget.Result resultado = execucao.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(resultado.exitCode()).isNotZero(); // morto por sinal, nao saida limpa
        aguardarMorte(childPid, Duration.ofSeconds(5));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                .as("o filho da tool nao pode sobreviver orfao ao interrupt")
                .isFalse();
    }

    private static long aguardarPid(Path pidFile, Duration teto) throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (Instant.now().isBefore(deadline)) {
            if (java.nio.file.Files.exists(pidFile)) {
                try {
                    String conteudo = java.nio.file.Files.readString(pidFile).trim();
                    if (!conteudo.isEmpty()) {
                        return Long.parseLong(conteudo);
                    }
                } catch (Exception aindaEscrevendo) {
                    // arquivo apareceu mas o conteudo ainda nao foi flushado — tenta de novo
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("child pid nao apareceu em " + teto);
    }

    private static void aguardarMorte(long pid, Duration teto) throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (Instant.now().isBefore(deadline)) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
