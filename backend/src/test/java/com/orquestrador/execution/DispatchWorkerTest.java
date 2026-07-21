package com.orquestrador.execution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * O {@code catch} bonito valia so para {@code RuntimeException}: um {@code Error} escapava dele e
     * ia direto ao handler default da thread. Nao travava (o {@code finally} libera a porta e o pool
     * respawna) — o que se perdia era a LINHA DE DIAGNOSTICO, que e o unico rastro de que a passada
     * caiu. Endurecimento dos residuos do PR #54.
     */
    @Test
    void errorNaPassadaTambemDeixaALinhaDeDiagnostico() throws Exception {
        AtomicInteger passadas = new AtomicInteger();
        HeartbeatDispatcher explosivo = mock(HeartbeatDispatcher.class);
        when(explosivo.dispatchOnce()).thenAnswer(invocacao -> {
            passadas.incrementAndGet();
            throw new StackOverflowError("pilha estourada dentro do dispatch");
        });

        ListAppender<ILoggingEvent> avisos = new ListAppender<>();
        avisos.start();
        ch.qos.logback.classic.Logger workerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DispatchWorker.class);
        workerLogger.addAppender(avisos);
        // O Error SOBE depois de logado (nao e "falha de passada", e a JVM em apuros) — o handler
        // default so imprimiria a pilha no stderr e sujaria a saida da suite.
        Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        AtomicReference<Throwable> subiu = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, erro) -> subiu.set(erro));
        try (DispatchWorker worker = new DispatchWorker(explosivo)) {
            worker.dispatchAsync();
            esperarPassadas(passadas, 1);
            // A porta tem de voltar mesmo sob Error, senao a parada silenciosa volta por outro caminho.
            tickAteAceitar(worker);
            esperarPassadas(passadas, 2);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(anterior);
            workerLogger.detachAppender(avisos);
        }

        assertThat(avisos.list)
                .filteredOn(evento -> evento.getLevel() == Level.WARN)
                .as("o Error tem de deixar a MESMA linha de diagnostico que a RuntimeException deixa")
                .anySatisfy(evento -> assertThat(evento.getFormattedMessage())
                        .contains("pilha estourada dentro do dispatch"));
        assertThat(subiu.get())
                .as("logar nao e engolir: um Error segue subindo depois da linha de diagnostico")
                .isInstanceOf(StackOverflowError.class);
    }

    /**
     * O {@code close()} era {@code shutdownNow()} e voltava na hora — e a thread do dispatch é
     * DAEMON, então nada segura a JVM depois disso.
     *
     * <p>O que importa não é o que o interrupt CANCELA, e sim o que ele DISPARA: a passada
     * interrompida desce por {@code ProcessoComPrazo.aguardar}, que escala a derrubada
     * ({@code docker rm -f} do container do agente) antes de propagar. Esse trabalho leva tempo, roda
     * DEPOIS da interrupção, e com {@code close()} voltando de imediato a JVM saía por cima dele —
     * container órfão pelo mesmo desfecho que a fatia acabou de fechar uma camada abaixo, só que por
     * outro caminho. Por isso a passada aqui faz o trabalho dela no {@code catch}, não antes dele.
     */
    @Test
    void closeEsperaAPassadaEmVooTerminarDeEncerrar() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1);
        AtomicBoolean derrubadaConcluiu = new AtomicBoolean(false);
        HeartbeatDispatcher preso = mock(HeartbeatDispatcher.class);
        when(preso.dispatchOnce()).thenAnswer(invocacao -> {
            entrou.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException oShutdownChegou) {
                // A derrubada do container: só COMEÇA quando o interrupt chega, e o sleep aqui roda
                // inteiro (o lançamento acima consumiu a flag) — o RED não é corrida com o scheduler.
                Thread.sleep(300);
                derrubadaConcluiu.set(true);
            }
            return 0;
        });

        DispatchWorker worker = new DispatchWorker(preso);
        worker.dispatchAsync();
        assertThat(entrou.await(3, TimeUnit.SECONDS)).isTrue();

        worker.close();

        assertThat(derrubadaConcluiu)
                .as("close() não pode voltar com a derrubada ainda em curso: a thread é daemon e a"
                        + " JVM sai por cima dela")
                .isTrue();
    }

    /**
     * O único caminho MUDO da fatia do #57: {@code close()} repunha a flag e não logava nada.
     *
     * <p>É a mesma forma de falha que o {@code BLOCO_DE_SAIDA_SEM_DESTINO} da fatia irmã existe para
     * combater, e que o ramo VIZINHO deste mesmo {@code close()} (o teto de 5s estourado) já trata
     * com WARN. A consequência é idêntica nos dois: a derrubada em curso pode não completar e o
     * container fica órfão — só que num deles o operador lê o porquê e no outro a aplicação desce em
     * silêncio.
     *
     * <p>A passada aqui IGNORA a própria interrupção de propósito: o pool não pode terminar enquanto
     * a espera é medida, senão {@code awaitTermination} voltaria {@code true} sem nunca esperar.
     */
    @Test
    void closeInterrompidoNaEsperaTambemDeixaLinhaDeDiagnostico() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1);
        AtomicBoolean liberar = new AtomicBoolean(false);
        HeartbeatDispatcher teimoso = mock(HeartbeatDispatcher.class);
        when(teimoso.dispatchOnce()).thenAnswer(invocacao -> {
            entrou.countDown();
            while (!liberar.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException oShutdownChegou) {
                    // De propósito: quem termina esta passada é o teste, não o shutdownNow.
                }
            }
            return 0;
        });

        ListAppender<ILoggingEvent> avisos = new ListAppender<>();
        avisos.start();
        ch.qos.logback.classic.Logger workerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DispatchWorker.class);
        workerLogger.addAppender(avisos);
        DispatchWorker worker = new DispatchWorker(teimoso);
        boolean flagRestaurada = false;
        try {
            worker.dispatchAsync();
            assertThat(entrou.await(3, TimeUnit.SECONDS)).isTrue();
            // Quem está PARANDO a aplicação leva interrupt (o shutdown hook do Boot é uma thread
            // como outra qualquer) — e é aqui que a espera de close() morre pela metade.
            Thread.currentThread().interrupt();

            worker.close();

            // Medir ANTES de limpar; limpar para não contaminar o resto da suíte.
            flagRestaurada = Thread.interrupted();
        } finally {
            liberar.set(true);
            workerLogger.detachAppender(avisos);
        }

        assertThat(avisos.list)
                .filteredOn(evento -> evento.getLevel() == Level.WARN)
                .as("desistir da espera em silêncio é o mesmo modo de falha que o ramo vizinho do"
                        + " teto já trata com WARN — a derrubada em curso pode não completar")
                .anySatisfy(evento -> assertThat(evento.getFormattedMessage())
                        .containsIgnoringCase("interrompid"));
        assertThat(flagRestaurada).as("logar não muda o contrato: a interrupção não é engolida").isTrue();
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
