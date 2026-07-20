package com.orquestrador.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0050, 2ª trava: o run do agente sai da thread do scheduler.
 *
 * <p>A thread do scheduler e UMA (default do Boot, por ausencia de config) e o
 * {@code HeartbeatDispatcher.dispatchOnce} chama {@code executor.execute} SINCRONAMENTE — o run
 * inteiro do agente rodava nela. Um unico processo pendurado parava dispatcher, lease-reaper, GC de
 * workspace, GC de preview e thumbnails de uma vez, <b>sem erro nenhum</b>: as coisas apenas
 * deixavam de acontecer. O prazo (1ª trava) limita o dano no tempo; isto tira o resto do motor da
 * frente dele.
 *
 * <p>A politica e PULAR, nao enfileirar: hoje o {@code fixedDelay} so re-agenda depois do retorno,
 * entao nunca ha duas passadas ao mesmo tempo. Enfileirar cada tick de 1s durante uma passada longa
 * inverteria isso e criaria uma fila que nunca drena.
 */
class DispatchWorkerTest {

    /** Uma passada que fica presa ate `solta` — com teto proprio, para o RED nunca pendurar a suite. */
    private static HeartbeatDispatcher dispatcherPreso(CountDownLatch entrou, CountDownLatch solta,
                                                       AtomicInteger passadas) {
        HeartbeatDispatcher preso = mock(HeartbeatDispatcher.class);
        when(preso.dispatchOnce()).thenAnswer(invocacao -> {
            passadas.incrementAndGet();
            entrou.countDown();
            solta.await(5, TimeUnit.SECONDS);
            return 0;
        });
        return preso;
    }

    @Test
    void passadaLentaNaoSeguraAThreadDeQuemADisparou() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch solta = new CountDownLatch(1);
        AtomicInteger passadas = new AtomicInteger();

        try (DispatchWorker worker = new DispatchWorker(dispatcherPreso(entrou, solta, passadas))) {
            // O @Scheduled tem de voltar na hora, com a passada ainda rodando — e o que devolve
            // `scheduling-1` para os outros cinco @Scheduled.
            assertTimeoutPreemptively(Duration.ofSeconds(2), worker::dispatchAsync);
            assertThat(entrou.await(3, TimeUnit.SECONDS))
                    .as("a passada tem de ter comecado de verdade, so que fora da thread do chamador")
                    .isTrue();
            solta.countDown();
        }
    }

    @Test
    void tickDurantePassadaEmVooEhPuladoEmVezDeEnfileirado() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch solta = new CountDownLatch(1);
        AtomicInteger passadas = new AtomicInteger();

        try (DispatchWorker worker = new DispatchWorker(dispatcherPreso(entrou, solta, passadas))) {
            assertThat(worker.dispatchAsync()).as("a primeira passada inicia").isTrue();
            assertThat(entrou.await(3, TimeUnit.SECONDS)).isTrue();

            for (int tick = 0; tick < 5; tick++) {
                assertThat(worker.dispatchAsync())
                        .as("tick %d com passada em voo tem de ser PULADO", tick)
                        .isFalse();
            }
            solta.countDown();
        }

        assertThat(passadas.get())
                .as("os ticks pulados nao podem ter virado fila para rodar depois")
                .isEqualTo(1);
    }

    @Test
    void depoisQueAPassadaTerminaOProximoTickRodaDeNovo() throws Exception {
        AtomicInteger passadas = new AtomicInteger();
        HeartbeatDispatcher rapido = mock(HeartbeatDispatcher.class);
        when(rapido.dispatchOnce()).thenAnswer(invocacao -> passadas.incrementAndGet());

        try (DispatchWorker worker = new DispatchWorker(rapido)) {
            worker.dispatchAsync();
            esperarPassadas(passadas, 1);
            tickAteAceitar(worker);
            esperarPassadas(passadas, 2);
        }

        assertThat(passadas.get()).isEqualTo(2);
    }

    /**
     * Uma passada que explode nao pode deixar a porta trancada — seria a parada silenciosa de novo,
     * so que por outro caminho.
     */
    @Test
    void passadaQueExplodeLiberaAPortaParaOProximoTick() throws Exception {
        AtomicInteger passadas = new AtomicInteger();
        HeartbeatDispatcher explosivo = mock(HeartbeatDispatcher.class);
        when(explosivo.dispatchOnce()).thenAnswer(invocacao -> {
            passadas.incrementAndGet();
            throw new IllegalStateException("banco fora");
        });

        try (DispatchWorker worker = new DispatchWorker(explosivo)) {
            worker.dispatchAsync();
            esperarPassadas(passadas, 1);
            tickAteAceitar(worker);
            esperarPassadas(passadas, 2);
        }

        assertThat(passadas.get()).isEqualTo(2);
    }

    private static void esperarPassadas(AtomicInteger passadas, int alvo) throws InterruptedException {
        for (int tentativa = 0; tentativa < 300 && passadas.get() < alvo; tentativa++) {
            Thread.sleep(10);
        }
        assertThat(passadas.get()).isGreaterThanOrEqualTo(alvo);
    }

    /**
     * O scheduler tica de novo a cada segundo, entao "roda de novo" quer dizer "algum tick seguinte e
     * aceito" — nao "o tick imediatamente posterior ao incremento do contador". O contador sobe
     * DENTRO da passada, um instante antes de a porta ser liberada; exigir o primeiro tick seria
     * corrida com esse instante, nao comportamento.
     */
    private static void tickAteAceitar(DispatchWorker worker) throws InterruptedException {
        for (int tentativa = 0; tentativa < 300; tentativa++) {
            if (worker.dispatchAsync()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("nenhum tick foi aceito depois de a passada anterior terminar");
    }
}
