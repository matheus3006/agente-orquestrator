package com.orquestrador.execution;

import com.orquestrador.execution.lineage.SpawnLineageGuard;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O teto de 5s do {@code DispatchWorker.close()} é o orçamento de UMA derrubada — o javadoc dele diz
 * isso — mas quem esperava por ele era a <b>passada inteira</b>: o laço de {@code dispatchOnce}
 * varre até 50 runs {@code QUEUED} e não olhava a flag de interrupção nenhuma vez. Depois do
 * {@code shutdownNow}, o laço seguia reivindicando os próximos, cada um subindo container e
 * escalando o próprio {@code docker rm -f} — <b>os 5s divididos por até 50 iterações</b>, e o limite
 * nomeado deixava de ser o limite real.
 *
 * <p>A checagem é de UMA linha e é o que faz os dois números coincidirem. Ela usa
 * {@code isInterrupted()}, nunca {@code Thread.interrupted()}: consumir a flag aqui mataria a
 * escalação lá embaixo, que é o defeito que esta esteira inteira vem fechando.
 *
 * <p>O molde do teste é o do motor real sem Spring: o {@code executor} interrompe a própria thread
 * no meio do primeiro run, que é exatamente o que {@code close()} → {@code shutdownNow} faz.
 */
class DispatchInterrompidoNaoReivindicaRunNovoTest {

    @Test
    void depoisDoShutdownNowOLacoNaoReivindicaOProximoRunDaFila() {
        UUID companyId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID primeiroRun = UUID.randomUUID();
        UUID segundoRun = UUID.randomUUID();

        HeartbeatRunRepository runs = mock(HeartbeatRunRepository.class);
        when(runs.findTop50ByStatusOrderByCreatedAtAsc(RunStatus.QUEUED)).thenReturn(List.of(
                new HeartbeatRun(primeiroRun, companyId, agentId, null, RunStatus.QUEUED),
                new HeartbeatRun(segundoRun, companyId, agentId, null, RunStatus.QUEUED)));

        HeartbeatRunStore store = mock(HeartbeatRunStore.class);
        when(store.claim(any(), any(), any())).thenReturn(true);
        when(store.contextFor(any())).thenAnswer(invocacao -> Optional.of(
                new RunContext(companyId, agentId, invocacao.getArgument(0), null)));

        List<UUID> executados = new ArrayList<>();
        RunExecutor executor = mock(RunExecutor.class);
        when(executor.execute(any())).thenAnswer(invocacao -> {
            executados.add(((RunContext) invocacao.getArgument(0)).runId());
            // O shutdown chegando no meio da passada — `shutdownNow` interrompe ESTA thread.
            Thread.currentThread().interrupt();
            return RunOutcome.success();
        });

        HeartbeatDispatcher dispatcher = new HeartbeatDispatcher(runs, store, executor,
                mock(BudgetService.class), mock(TeamService.class), mock(AgentRepository.class),
                mock(SpawnLineageGuard.class), mock(TeamFanOutGate.class), mock(LeaseRenewer.class));

        int despachados;
        boolean flagSobreviveuAoLaco;
        try {
            despachados = dispatcher.dispatchOnce();
        } finally {
            // Consumir aqui é higiene da suíte (a thread do JUnit é reusada), e só DEPOIS de medir.
            flagSobreviveuAoLaco = Thread.interrupted();
        }

        assertThat(executados)
                .as("o run que já estava em voo termina; o SEGUINTE não pode nem começar — é ele que"
                        + " sobe container novo com o shutdown em curso")
                .containsExactly(primeiroRun);
        verify(store, never()).claim(eq(segundoRun), any(), any());
        assertThat(despachados).isEqualTo(1);
        assertThat(flagSobreviveuAoLaco)
                .as("a checagem não pode CONSUMIR a flag: sem ela posta, a escalação da derrubada"
                        + " morre no primeiro ponto de espera — o defeito que o #57 fechou")
                .isTrue();
    }
}
