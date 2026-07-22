package com.orquestrador.execution;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
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
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
    private ActivityLogRepository activityLogs;
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

        ActivityLog rastro = activityLogs
                .findFirstByCompanyIdAndEntityIdAndActionOrderByCreatedAtDesc(companyId, issueId,
                        "ISSUE_BLOCKED")
                .orElseThrow(() -> new AssertionError("a liquidação do preflight não deixou rastro"));

        assertSoftly(softly -> {
            softly.assertThat(runs.findById(runId).orElseThrow().getStatus())
                    .isEqualTo(RunStatus.CANCELLED);
            softly.assertThat(issues.get(companyId, issueId).getStatus())
                    .isEqualTo(IssueStatus.BLOCKED);
            softly.assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();
            // Fatia I-ter, B1 (decisão do dono): nos 4 preflights o run é barrado ANTES do claim —
            // o critério é orçamento, estrutura do time, cap do arquiteto e linhagem, e o agente é
            // o OBJETO da decisão, não o sujeito. Com AGENT aqui, ISSUE_BLOCKED significava duas
            // coisas incompatíveis ("o run rodou e falhou" × "o run nem chegou a rodar"). A
            // partição vira aprendível: AGENT = houve execução; SYSTEM = a orquestração decidiu sem
            // execução.
            softly.assertThat(rastro.getActorType())
                    .as("preflight barra ANTES do claim: a orquestração decidiu, não o agente")
                    .isEqualTo(ActorType.SYSTEM.name());
            softly.assertThat(rastro.getActorId())
                    .as("SYSTEM não tem ator individual (precedente: WorkspaceReaper)")
                    .isNull();
        });
    }

    /**
     * <b>A PARTIÇÃO do ator, com as DUAS metades na mesma varredura</b> (Fatia I-ter, B1).
     *
     * <p>O teste acima prova que o preflight grava {@code SYSTEM}. Sozinho, ele passaria também
     * numa implementação que gravasse {@code SYSTEM} <b>sempre</b> — e aí a troca não teria criado
     * partição nenhuma, só trocado um rótulo errado por outro. O que dá sentido a {@code SYSTEM} é
     * existir um caso vizinho que continua {@code AGENT}.
     *
     * <p>As duas metades, no mesmo bloco: um run que <b>executou e falhou</b> (o caminho do
     * {@code AdapterRunExecutor}) deixa {@code AGENT} + o assignee; um run <b>barrado no
     * preflight</b>, que nunca chegou ao {@code claim}, deixa {@code SYSTEM} sem ator. É essa
     * diferença que alguém lendo o feed — ou escrevendo diagnóstico em cima dele — pode usar:
     * <b>{@code AGENT} = houve execução; {@code SYSTEM} = a orquestração decidiu sem execução.</b>
     */
    @Test
    void oATORparteAGUAS_execucaoEhDoAGENTE_preflightEhDoSISTEMA() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia a fila global (contexto/H2 compartilhados)
        }
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        // METADE 1 — o run EXECUTOU e falhou: o settle é ato do agente que rodou.
        UUID issueExecutada = issues.create(companyId, "rodou e falhou", null).getId();
        UUID runQueRodou = wakeups.enqueue(companyId, agentId, issueExecutada, "trabalhar").runId();
        assertThat(issues.checkout(companyId, issueExecutada, agentId, runQueRodou).acquired()).isTrue();
        issues.settleAfterRun(companyId, issueExecutada, runQueRodou, RunOutcome.failure("boom"));

        // METADE 2 — o run é BARRADO no preflight, antes do claim: a orquestração decidiu.
        UUID issueBarrada = issues.create(companyId, "barrada antes de rodar", null).getId();
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 100, null);
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 200, 0, 0);
        UUID runBarrado = wakeups.enqueue(companyId, agentId, issueBarrada, "trabalhar").runId();
        assertThat(issues.checkout(companyId, issueBarrada, agentId, runBarrado).acquired()).isTrue();
        dispatcher.dispatchOnce();

        ActivityLog daExecucao = rastroDeBloqueio(companyId, issueExecutada);
        ActivityLog doPreflight = rastroDeBloqueio(companyId, issueBarrada);

        assertSoftly(softly -> {
            softly.assertThat(daExecucao.getActorType())
                    .as("houve execução: o ato é do agente que rodou")
                    .isEqualTo(ActorType.AGENT.name());
            softly.assertThat(daExecucao.getActorId())
                    .as("e AGENT tem ator individual — o assignee fixado no checkout")
                    .isEqualTo(agentId);
            softly.assertThat(doPreflight.getActorType())
                    .as("não houve execução: a orquestração decidiu sozinha")
                    .isEqualTo(ActorType.SYSTEM.name());
            softly.assertThat(doPreflight.getActorId())
                    .as("e SYSTEM não tem ator individual")
                    .isNull();
        });
    }

    private ActivityLog rastroDeBloqueio(UUID companyId, UUID issueId) {
        return activityLogs
                .findFirstByCompanyIdAndEntityIdAndActionOrderByCreatedAtDesc(companyId, issueId,
                        "ISSUE_BLOCKED")
                .orElseThrow(() -> new AssertionError("sem rastro ISSUE_BLOCKED para a issue " + issueId));
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
