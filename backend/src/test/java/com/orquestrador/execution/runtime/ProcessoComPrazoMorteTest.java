package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * <b>Chamar a escalação não é escalar</b> — o 🔴 que sobrou do PR #56.
     *
     * <p>O teste acima prova que {@code escalarMorte} é INVOCADA; nunca poderia provar que ela
     * TERMINA, porque o runnable dele (um {@code set(true)}) não espera por nada. Só que a escalação
     * de verdade espera: {@code docker rm -f} é um subprocesso, e toda espera por subprocesso morre
     * NA HORA se a flag de interrupção da thread já estiver reposta. Com o
     * {@code Thread.currentThread().interrupt()} antes do {@code matar(...)}, a escalação era
     * interrompida no primeiro ponto de espera — o container ficava órfão exatamente como antes do
     * #56, só que agora com um teste verde por cima.
     *
     * <p>Por isso a asserção aqui é sobre o efeito DEPOIS da espera, e a escalação usa
     * {@code Thread.sleep} — o ponto de espera mais sensível a interrupção que existe: com a flag
     * posta ele lança antes de dormir um milissegundo, sem ela dorme e conclui. Determinístico nos
     * dois sentidos, que é o que faltava.
     */
    @Test
    void interrupcaoNaoMataAPROPRIAEscalacao() throws Exception {
        Process processo = new ProcessBuilder("sh", "-c", "sleep 3600").start();
        AtomicBoolean derrubadaConcluiu = new AtomicBoolean(false);
        AtomicReference<Exception> propagada = new AtomicReference<>();
        CountDownLatch coletaPendurada = new CountDownLatch(1);

        Thread chamador = new Thread(() -> {
            try {
                ProcessoComPrazo.aguardar("interrupcao", processo, Duration.ofSeconds(30),
                        () -> {
                            coletaPendurada.countDown();
                            return processo.getInputStream().read();
                        },
                        () -> {
                            try {
                                Thread.sleep(50);
                                derrubadaConcluiu.set(true);
                            } catch (InterruptedException morreuNoMeioDaDerrubada) {
                                // É AQUI que o `docker rm -f` morria. Não engolir: a flag volta.
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Exception excecao) {
                propagada.set(excecao);
            }
        }, "chamador-do-teste");

        try {
            chamador.start();
            assertThat(coletaPendurada.await(10, TimeUnit.SECONDS)).isTrue();
            chamador.interrupt();
            chamador.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(derrubadaConcluiu)
                    .as("a derrubada tem de CONCLUIR, não só começar — senão o container fica órfão")
                    .isTrue();
            assertThat(propagada.get()).isInstanceOf(InterruptedException.class);
        } finally {
            processo.destroyForcibly();
        }
    }

    /**
     * O mesmo defeito no molde EXATO do Docker, uma camada abaixo.
     *
     * <p>A escalação real não é um {@code sleep}: {@code DockerExecutionTarget.interrupt} chama
     * {@code exec}, que é <b>outro {@code ProcessoComPrazo.aguardar}</b>, na MESMA thread. Esse
     * aninhado espera em {@code tarefa.get} — e {@code FutureTask.awaitDone} lança
     * {@code InterruptedException} imediatamente quando a flag está posta, sem sequer olhar o
     * processo. O {@code rm -f} então levava {@code destroyForcibly} do próprio aninhado e voltava
     * como {@code exit -1}: o cliente morto, o container vivo.
     *
     * <p>Existe junto com o teste acima de propósito — aquele descreve o CONTRATO ("a escalação
     * conclui"), este congela a FORMA que a produção usa. Um conserto futuro que só olhasse
     * {@code Thread.sleep} continuaria deixando container órfão.
     */
    @Test
    void escalacaoQueEhUmAGUARDARANINHADOTambemConclui() throws Exception {
        Process processo = new ProcessBuilder("sh", "-c", "sleep 3600").start();
        Process derrubada = new ProcessBuilder("sh", "-c", "exit 7").start();
        AtomicInteger saidaDaDerrubada = new AtomicInteger(-1);
        CountDownLatch coletaPendurada = new CountDownLatch(1);

        Thread chamador = new Thread(() -> {
            try {
                ProcessoComPrazo.aguardar("interrupcao", processo, Duration.ofSeconds(30),
                        () -> {
                            coletaPendurada.countDown();
                            return processo.getInputStream().read();
                        },
                        () -> {
                            try {
                                // O sleep está na COLETA (thread própria, nunca interrompida): ele só
                                // garante que o aninhado ainda não terminou quando o `tarefa.get` é
                                // chamado. Sem isso o RED viraria corrida com o spawn do subprocesso.
                                ProcessoComPrazo.Resultado<Integer> rmMenosF = ProcessoComPrazo.aguardar(
                                        "rm -f aninhado", derrubada, Duration.ofSeconds(10),
                                        () -> {
                                            Thread.sleep(150);
                                            return derrubada.waitFor();
                                        });
                                if (rmMenosF.concluiu()) {
                                    saidaDaDerrubada.set(rmMenosF.colhido());
                                }
                            } catch (Exception morreuNoAninhado) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Exception ignorada) {
                // A propagação já é asserção do teste acima.
            }
        }, "chamador-do-teste");

        try {
            chamador.start();
            assertThat(coletaPendurada.await(10, TimeUnit.SECONDS)).isTrue();
            chamador.interrupt();
            chamador.join(TimeUnit.SECONDS.toMillis(20));

            assertThat(saidaDaDerrubada)
                    .as("o `aguardar` aninhado da escalação tem de rodar até o fim e colher o exit"
                            + " real do subprocesso — não morrer na flag do chamador")
                    .hasValue(7);
        } finally {
            processo.destroyForcibly();
            derrubada.destroyForcibly();
        }
    }
}
