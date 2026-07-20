package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * ADR-0050: o plano de CONTROLE do daemon tem prazo. Estes eram os {@code exec()} sem saida nenhuma
 * — nem recuperacao automatica (o reaper vive na thread que eles travam) nem manual (o
 * {@code RunInterruptService} so alcanca o run, nao o {@code volume ls}): ali so restava restart do
 * processo. O argv NAO e docker de proposito: o alvo do teste e o transporte, e um {@code sh -c} que
 * pendura prova o prazo sem depender de daemon nenhum, como o resto da suite deste pacote.
 */
class DockerExecutionTargetPrazoTest {

    @Test
    void comandoDeControleQuePenduraEstouraOPrazoEmVezDeTravarAThread() {
        DockerExecutionTarget target = new DockerExecutionTarget(
                PrazosDeExecucao.comPrazos(Duration.ofMillis(500), Duration.ofSeconds(30),
                        Duration.ofSeconds(30), Duration.ofSeconds(30)));

        var resultado = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> target.exec(List.of("sh", "-c", "sleep 3600")));

        assertThat(resultado.exitCode()).as("estouro tem de ser falha, nunca exit 0").isNotZero();
        assertThat(resultado.output()).contains("prazo");
    }

    /** O caminho feliz nao pode ter mudado: comando que responde devolve exit e saida de sempre. */
    @Test
    void comandoQueRespondeDentroDoPrazoSegueDevolvendoExitESaida() {
        DockerExecutionTarget target = new DockerExecutionTarget(
                PrazosDeExecucao.comPrazos(Duration.ofSeconds(10), Duration.ofSeconds(30),
                        Duration.ofSeconds(30), Duration.ofSeconds(30)));

        var resultado = target.exec(List.of("sh", "-c", "echo ok; exit 3"));

        assertThat(resultado.exitCode()).isEqualTo(3);
        assertThat(resultado.output()).contains("ok");
    }
}
