package com.orquestrador.governance.budget;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.LockOrfaoReaper;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O DRAIN EM LOTE do hard-stop deixa rastro, e as Issues que ele órfã são colhidas pela rede
 * (Fatia I, peça 3 + a amarração com a peça 2).
 *
 * <p>{@code drainScopeWorkToCheckpoint} cancela por UPDATE em massa
 * ({@code cancelByCompany}/{@code cancelByAgent}) — um caminho que <b>não passa pelo
 * {@code HeartbeatRunStore}</b>, onde mora o {@code recordEvent}. Resultado: os runs viravam
 * {@code CANCELLED} <b>sem uma linha de evidência</b>, e as Issues que eles detinham ficavam
 * carimbadas sem que nada dissesse por quê. Órfão sem rastro é órfão sem diagnóstico.
 *
 * <p>Este é o produtor <b>em lote</b>: órfã N Issues de uma vez, e é o mais provável de disparar
 * (o run caro é justamente o que trava e perde o lease).
 */
@SpringBootTest
@ActiveProfiles("test")
class DrainEmLoteDeixaRastroTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository events;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private LockOrfaoReaper lockOrfaoReaper;

    @Test
    void hardStopEmLoteGravaRunCancelledEmCadaRunEALiberaAsIssuesPelaRede() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        // Dois runs na fila, cada um DETENDO a sua Issue (checkout feito, lease vencido, requeue).
        UUID issueA = issues.create(companyId, "tarefa A", null).getId();
        UUID issueB = issues.create(companyId, "tarefa B", null).getId();
        UUID runA = enfileirarDetendo(companyId, agentId, issueA);
        UUID runB = enfileirarDetendo(companyId, agentId, issueB);

        // Estoura o teto do AGENTE: o enforce reativo abre incidente e DRENA o escopo em lote.
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 100, null);
        budgets.recordCost(companyId, agentId, null, null, "openai", "gpt", 200, 0, 0);

        assertThat(runs.findById(runA).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runCancelledDe(runA)).isNotEmpty();
        assertThat(runCancelledDe(runB)).isNotEmpty();

        // A REDE colhe o que o lote órfã: o drain não tem runId individual na mão para liquidar.
        lockOrfaoReaper.reapOnce();

        assertThat(issues.get(companyId, issueA).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(issues.get(companyId, issueB).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }

    private UUID enfileirarDetendo(UUID companyId, UUID agentId, UUID issueId) {
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.QUEUED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        return runId;
    }

    private List<HeartbeatRunEvent> runCancelledDe(UUID runId) {
        return events.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(evento -> "RUN_CANCELLED".equals(evento.getType()))
                .toList();
    }
}
