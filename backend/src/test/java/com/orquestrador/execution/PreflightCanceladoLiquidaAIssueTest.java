package com.orquestrador.execution;

import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.budget.ScopeType;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O preflight que CANCELA um run também LIQUIDA a Issue que esse run detinha (Fatia I, peça 1).
 *
 * <p>A mecânica do órfão, que este teste reproduz: o run R faz checkout da Issue
 * ({@code execution_lock_run_id = R}), o nó trava, o lease expira, o {@code LeaseReaper} devolve R
 * para {@code QUEUED} — <b>com o carimbo na Issue de propósito</b>, é o mesmo R que voltaria a
 * rodar — e na volta um preflight o barra. Antes desta fatia {@code HeartbeatRunStore.cancel} só
 * virava o status do run: a Issue ficava {@code IN_PROGRESS} carimbada com um run que nunca mais
 * roda, impegável pelo {@code tryCheckout} (que exige a coluna null-ou-igual).
 *
 * <p>O irmão — run que NÃO detinha a Issue ⇒ no-op, vítima intacta — é o
 * {@code BudgetPreflightTest.runEntraComEscopoPausadoEhCanceladoSemExecutar}, que já existia e
 * segue verde: o guard {@code execution_lock_run_id = runId} de {@code tryBlock} casa 0 linhas.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreflightCanceladoLiquidaAIssueTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void preflightDeBudgetCancelaORunEDevolveAIssueQueEleDetinha() {
        // Contexto/H2 compartilhados: outros testes deixam runs QUEUED legítimos na fila global.
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia a fila global
        }
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa que morre de pé", null).getId();

        // O escopo estoura ANTES do run nascer: assim o run entra na fila e é barrado pelo
        // PREFLIGHT (o drain em lote do hard-stop é outro produtor, coberto por outro teste).
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 100, null);
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 200, 0, 0);

        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();
        // O run DETINHA a Issue e voltou para a fila (lease vencido → requeueExpired): o carimbo
        // continua nele. É este o estado em que o preflight o encontra.
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        dispatcher.dispatchOnce();

        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();
    }

    /**
     * <b>O CINTO que faltava</b> (Fatia I-bis, item 5) — achado por 2 verificadores independentes.
     *
     * <p>O {@code LockOrfaoReaper.reapOnce} declara a doutrina por escrito: <i>"uma Issue que
     * exploda não pode levar as demais junto"</i>. A MESMA doutrina não estava aplicada 30 linhas
     * ao lado: o {@code cancelarELiquidar} chamava {@code settleAfterRun} <b>sem cinto</b>, dentro
     * do {@code for} de até 50 runs do {@code dispatchOnce}. Um throw ali não estragava uma Issue —
     * <b>descartava o resto da passada</b>, e os runs seguintes da fila não eram nem olhados.
     *
     * <p>O ato que explode é o recurso da casa para provar cinto ({@code semDerrubarOSettle}), pelo
     * mesmo motivo: o settle real não explode sob demanda, e a casa não mocka bean. A FIAÇÃO — que
     * o caminho normal de fato passa pelo cinto — é o teste acima que prova: sem o cinto no
     * caminho, a Issue não teria sido liquidada.
     */
    @Test
    void liquidacaoQueEXPLODE_naoDerrubaOrestoDaPassada() {
        HeartbeatRun run = new HeartbeatRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), RunStatus.CANCELLED);

        assertThatCode(() -> dispatcher.semDerrubarAPassada(run, () -> {
            throw new IllegalStateException("regressão de amanhã");
        })).as("se isto sobe, o dispatchOnce descarta os runs seguintes da MESMA passada")
                .doesNotThrowAnyException();
    }
}
