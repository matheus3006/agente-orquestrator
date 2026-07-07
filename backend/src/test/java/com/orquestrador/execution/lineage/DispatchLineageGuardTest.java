package com.orquestrador.execution.lineage;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.execution.LeaseReaper;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O guard do spawn_lineage (T.4; ADR-0016 rev. 2026-07-04) nas DUAS faces do funil — espelha a
 * trava do arquiteto de T.2:
 * 1) ENQUEUE — acordar um ANCESTRAL da própria linhagem = 409 sem criar run/wakeup (mata o loop
 *    A→B→A; auto-wake é o caso degenerado A→A) e cadeia funda demais = 409 (cap de profundidade);
 * 2) CHECKOUT — um run que NASCEU ilegal (entrou por fora do enqueue, ex.: promote) é CANCELADO
 *    no preflight do dispatcher sem executar.
 */
@SpringBootTest
@ActiveProfiles("test")
class DispatchLineageGuardTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private LeaseReaper reaper;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private AgentRepository agents;

    @Test
    void acordarAncestralDaPropriaLinhagemLeva409SemCriarRun() {
        UUID companyId = UUID.randomUUID();
        Agent ceo = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));
        Agent executor = agents.save(new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null));

        // trabalho causado pelo CEO acordando o próprio CEO (ancestral) = loop → recusado
        SpawnLineage causadaPeloCeo = SpawnLineage.root().descendTo(ceo.getId());
        assertThatThrownBy(() -> wakeups.enqueue(companyId, ceo.getId(), null, "loop", causadaPeloCeo))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("linhagem");
        assertThat(runs.findByAgentAndStatuses(ceo.getId(),
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING))).isEmpty();

        // a MESMA cadeia acordando um NÃO-ancestral passa — e o run nasce com a linhagem
        UUID runId = wakeups.enqueue(companyId, executor.getId(), null, "delegar", causadaPeloCeo).runId();
        HeartbeatRun run = runs.findById(runId).orElseThrow();
        assertThat(SpawnLineage.decode(run.getSpawnLineage()).contains(ceo.getId())).isTrue();
    }

    @Test
    void cadeiaFundaDemaisLeva409() {
        UUID companyId = UUID.randomUUID();
        Agent alvo = agents.save(new Agent(UUID.randomUUID(), companyId, "Fundo", "executor", null));

        SpawnLineage funda = SpawnLineage.root();
        for (int nivel = 0; nivel < 6; nivel++) {
            funda = funda.descendTo(UUID.randomUUID());
        }

        SpawnLineage cadeiaAcimaDoCap = funda;
        assertThatThrownBy(() -> wakeups.enqueue(companyId, alvo.getId(), null, "fundo", cadeiaAcimaDoCap))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("profundidade");
    }

    @Test
    void promoteOnFinalizeHerdaALinhagemMaisFundaDosDeferidos() {
        UUID companyId = UUID.randomUUID();
        Agent ceo = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));
        Agent lider = agents.save(new Agent(UUID.randomUUID(), companyId, "Lider", "cto", null));
        Agent agente = agents.save(new Agent(UUID.randomUUID(), companyId, "Beta", "executor", null));

        // Run 1 em execução; chegam DOIS wakeups deferidos: um raiz e um com cadeia CEO→líder
        UUID run1 = wakeups.enqueue(companyId, agente.getId(), null, "primeiro").runId();
        assertThat(store.claim(run1, "node-lineage", java.time.Duration.ofMinutes(2))).isTrue();
        wakeups.enqueue(companyId, agente.getId(), null, "raiz-durante-o-run");
        SpawnLineage cadeiaFunda = SpawnLineage.root().descendTo(ceo.getId()).descendTo(lider.getId());
        wakeups.enqueue(companyId, agente.getId(), null, "funda-durante-o-run", cadeiaFunda);

        store.finishSuccess(run1, "node-lineage");

        // o Run promovido NÃO lava a cadeia: herda a mais funda dos deferidos
        HeartbeatRun promovido = runs.findByAgentAndStatuses(agente.getId(),
                List.of(RunStatus.QUEUED)).get(0);
        SpawnLineage herdada = SpawnLineage.decode(promovido.getSpawnLineage());
        assertThat(herdada.depth()).isEqualTo(2);
        assertThat(herdada.contains(ceo.getId())).isTrue();

        // drena o run promovido: a classe enfileira sem despachar (gotcha do contexto compartilhado)
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia a fila global
        }
    }

    @Test
    void linhagemSobreviveAoReaper() {
        UUID companyId = UUID.randomUUID();
        Agent ceo = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));
        Agent agente = agents.save(new Agent(UUID.randomUUID(), companyId, "Gama", "executor", null));

        SpawnLineage causadaPeloCeo = SpawnLineage.root().descendTo(ceo.getId());
        UUID runId = wakeups.enqueue(companyId, agente.getId(), null, "trabalho", causadaPeloCeo).runId();
        assertThat(store.claim(runId, "node-morto", java.time.Duration.ofMillis(-1))).isTrue();

        reaper.reapOnce(); // lease já expirado → volta pra fila

        HeartbeatRun requeued = runs.findById(runId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(SpawnLineage.decode(requeued.getSpawnLineage()).contains(ceo.getId())).isTrue();

        while (dispatcher.dispatchOnce() > 0) {
            // esvazia a fila global (gotcha do contexto compartilhado)
        }
    }

    @Test
    void runQueNasceuIlegalPorForaDoEnqueueEhCanceladoNoCheckout() {
        UUID companyId = UUID.randomUUID();
        Agent agente = agents.save(new Agent(UUID.randomUUID(), companyId, "Loop", "executor", null));

        // run plantado DIRETO com o próprio agente na linhagem (entrou por fora do enqueue)
        SpawnLineage cicloMaterializado = SpawnLineage.root().descendTo(agente.getId());
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agente.getId(), null,
                RunStatus.QUEUED, cicloMaterializado)).getId();

        dispatcher.dispatchOnce();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
