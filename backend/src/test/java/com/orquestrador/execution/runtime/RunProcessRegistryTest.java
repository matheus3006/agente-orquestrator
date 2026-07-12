package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * F-N4 (ADR-0040, D4): a sonda R6-B provou que os dois motores vazam o processo-filho da tool
 * ORFAO a um SIGKILL so-do-pai (perl/cat sobrevivem reparented). Este registro e o mecanismo que
 * fecha o requisito: mata os DESCENDENTES antes do processo raiz — o mesmo molde que
 * {@code LocalExecutionTarget.stopPreviewServer} ja usa para servidores de preview.
 */
class RunProcessRegistryTest {

    @TempDir
    Path workDir;

    @Test
    void interruptMataODescendenteAntesDeMatarOProcessoRaiz() throws Exception {
        RunProcessRegistry registry = new RunProcessRegistry();
        UUID runId = UUID.randomUUID();

        // O molde da sonda: um pai (sh) cujo filho (cat lendo /dev/zero) e o "tool call" demorado —
        // cat/perl evadem o guard do ambiente contra `sleep` puro, e nunca da EOF sozinho.
        ProcessBuilder builder = new ProcessBuilder("sh", "-c",
                "cat /dev/zero > /dev/null & echo $! > childpid; wait");
        builder.directory(workDir.toFile());
        Process pai = builder.start();
        try {
            registry.register(runId, pai);

            long childPid = aguardarPid(workDir.resolve("childpid"), Duration.ofSeconds(5));
            assertThat(ProcessHandle.of(childPid)).isPresent();
            assertThat(ProcessHandle.of(childPid).orElseThrow().isAlive()).isTrue();

            boolean achouEMatou = registry.interrupt(runId);

            assertThat(achouEMatou).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThat(pai.waitFor(5, TimeUnit.SECONDS)).isTrue());
            aguardarMorte(childPid, Duration.ofSeconds(5));
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                    .as("o filho da tool nao pode sobreviver orfao ao interrupt")
                    .isFalse();
        } finally {
            pai.destroyForcibly();
        }
    }

    @Test
    void interruptDeRunIdNaoRegistradoDevolveFalse() {
        RunProcessRegistry registry = new RunProcessRegistry();

        assertThat(registry.interrupt(UUID.randomUUID())).isFalse();
    }

    @Test
    void unregisterRemoveOProcessoEInterruptDeixaDeAchaLo() throws Exception {
        RunProcessRegistry registry = new RunProcessRegistry();
        UUID runId = UUID.randomUUID();
        Process processo = new ProcessBuilder("cat", "/dev/zero").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            registry.register(runId, processo);
            registry.unregister(runId);

            assertThat(registry.interrupt(runId)).isFalse();
            assertThat(processo.isAlive()).isTrue(); // unregister nao mata; so tira do mapa
        } finally {
            processo.destroyForcibly();
        }
    }

    private static long aguardarPid(Path pidFile, Duration teto) throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(pidFile)) {
                try {
                    String conteudo = Files.readString(pidFile).trim();
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
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == Boolean.FALSE) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
