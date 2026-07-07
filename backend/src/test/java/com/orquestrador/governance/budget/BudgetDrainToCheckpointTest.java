package com.orquestrador.governance.budget;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "DRENA ATÉ O CHECKPOINT" (T.4; §A.6-#3 do grilling): o hard-stop cancela só o que ainda NÃO
 * começou (fila + wakeups); o trabalho EM VOO termina o passo e liquida no checkpoint (settle/
 * report) — matar RUNNING no banco nunca matou o processo, só jogava o resultado fora. Nada NOVO
 * nasce depois: o follow-up morre no preflight (escopo pausado).
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetDrainToCheckpointTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void hardStopCancelaSoAFilaEDeixaOEmVooFecharNoCheckpoint() {
        // Drena a fila global ANTES: o ==0 do fim tem que ser SÓ desta company.
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID emVoo = agents.save(new Agent(UUID.randomUUID(), companyId, "Voo", "worker", null)).getId();
        UUID naFila = agents.save(new Agent(UUID.randomUUID(), companyId, "Fila", "worker", null)).getId();
        budgets.createPolicy(companyId, ScopeType.COMPANY, companyId, 100, null);

        UUID runEmVoo = wakeups.enqueue(companyId, emVoo, null, "voando").runId();
        assertThat(store.claim(runEmVoo, "node-t4", Duration.ofMinutes(2))).isTrue();
        UUID runNaFila = wakeups.enqueue(companyId, naFila, null, "esperando").runId();
        // chega mais um pedido para quem está em voo → DEFERRED (needs_followup)
        wakeups.enqueue(companyId, emVoo, null, "mais um durante o voo");

        // o custo cruza o teto: incidente + drenagem do escopo
        budgets.recordCost(companyId, emVoo, runEmVoo, null, "openai", "gpt", 200, 100, 50);
        assertThat(budgets.invocationBlocked(companyId, null)).isTrue();

        // a FILA morre; o EM-VOO segue vivo até o checkpoint
        assertThat(runs.findById(runNaFila).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runs.findById(runEmVoo).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);

        // o checkpoint fecha de verdade: o resultado do passo NÃO é jogado fora
        store.finishSuccess(runEmVoo, "node-t4");
        assertThat(runs.findById(runEmVoo).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);

        // e nada NOVO executa: o follow-up promovido (se nasceu) morre no preflight
        assertThat(dispatcher.dispatchOnce()).isZero();
    }
}
