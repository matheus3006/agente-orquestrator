package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DERRUBADA tem de ser a mesma pelos DOIS caminhos de morte (endurecimento dos resíduos do PR #54).
 *
 * <p>O ramo de estouro de prazo já escalava; o de interrupção não, e é justamente ele que roda no
 * shutdown da JVM ({@code DispatchWorker.close} → {@code shutdownNow} → {@code interrupt}). Sem
 * escalação ali, o {@code destroyForcibly} atinge só o CLIENTE {@code docker run} — que não repassa
 * sinal — e o container do agente fica ÓRFÃO, vivo e invisível depois que a aplicação desceu.
 *
 * <p>O argv não é docker de propósito (mesma razão do {@code DockerExecutionTargetPrazoTest}): o
 * alvo é o transporte, e um {@code sh -c} que pendura prova a simetria sem depender de daemon.
 */
class ProcessoComPrazoMorteTest {

    /** O par negativo do teste abaixo: o caminho que JÁ escalava não pode regredir. */
    @Test
    void estouroDePrazoEscalaADerrubada() throws Exception {
        Process processo = new ProcessBuilder("sh", "-c", "sleep 3600").start();
        AtomicBoolean escalou = new AtomicBoolean(false);
        try {
            ProcessoComPrazo.Resultado<Integer> resultado = ProcessoComPrazo.aguardar(
                    "estouro", processo, Duration.ofMillis(300),
                    () -> processo.getInputStream().read(),
                    () -> escalou.set(true));

            assertThat(resultado.estourou()).isTrue();
            assertThat(escalou).as("o ramo de timeout escala a derrubada").isTrue();
        } finally {
            processo.destroyForcibly();
        }
    }

    @Test
    void interrupcaoTambemEscalaADerrubada() throws Exception {
        Process processo = new ProcessBuilder("sh", "-c", "sleep 3600").start();
        AtomicBoolean escalou = new AtomicBoolean(false);
        AtomicReference<Exception> propagada = new AtomicReference<>();
        AtomicBoolean flagRestaurada = new AtomicBoolean(false);
        // A coleta avisa que ENTROU antes de pendurar no stdout do processo (que nunca dá EOF):
        // daí em diante o chamador está em `tarefa.get`, e o interrupt não depende de sleep nenhum.
        CountDownLatch coletaPendurada = new CountDownLatch(1);

        Thread chamador = new Thread(() -> {
            try {
                ProcessoComPrazo.aguardar("interrupcao", processo, Duration.ofSeconds(30),
                        () -> {
                            coletaPendurada.countDown();
                            return processo.getInputStream().read();
                        },
                        () -> escalou.set(true));
            } catch (Exception excecao) {
                propagada.set(excecao);
                flagRestaurada.set(Thread.currentThread().isInterrupted());
            }
        }, "chamador-do-teste");

        try {
            chamador.start();
            assertThat(coletaPendurada.await(10, TimeUnit.SECONDS)).isTrue();
            chamador.interrupt();
            chamador.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(escalou)
                    .as("o ramo de interrupt tem de escalar como o de timeout — senão o container fica órfão")
                    .isTrue();
            assertThat(propagada.get()).isInstanceOf(InterruptedException.class);
            assertThat(flagRestaurada).as("a interrupção nunca é engolida").isTrue();
        } finally {
            processo.destroyForcibly();
        }
    }
}
