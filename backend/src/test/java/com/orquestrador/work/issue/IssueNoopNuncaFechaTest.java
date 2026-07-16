package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trava do bug #1 do roadmap de ajustes (ADR-0011 emendada, R22-A): um run que NÃO FEZ NADA
 * (no-op sinalizado pelo adapter — ex.: arquétipo process sem comando) NUNCA fecha a Issue.
 * Invariante do dono (grill 2026-07-16): "run que não fez nada nunca mais fecha issue" — sem ela,
 * comando vazio por qualquer causa faria o board voltar a mentir (DONE fantasma do run e67ff7dd,
 * 58ms, zero motor).
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueNoopNuncaFechaTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ActivityLogRepository activityLog;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", null, "LOCAL");
        return agents.save(agent).getId();
    }

    private long countAction(UUID companyId, ActivityAction action) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entry -> entry.getAction().equals(action.name())).count();
    }

    @Test
    void runNoopEstacionaEmBlockedNuncaDone() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = UUID.randomUUID();
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        assertThat(issues.settleAfterRun(companyId, issueId, runId,
                RunOutcome.noop("run no-op: agente process sem runtime_command")))
                .isEqualTo(IssueSettlement.BLOCKED);

        Issue parked = issues.get(companyId, issueId);
        assertThat(parked.getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(parked.getExecutionLockRunId()).isNull(); // lock liberado — recovery ADR-0011
        assertThat(parked.getAssigneeAgentId()).isEqualTo(agentId); // REOPEN mantém o agente
        assertThat(countAction(companyId, ActivityAction.ISSUE_BLOCKED)).isEqualTo(1);
    }

    @Test
    void noopMarcadoComoSucessoAindaAssimNuncaCompleta() {
        // O cinto NO SETTLE (onde o DONE é cunhado): mesmo que um caller futuro construa um
        // outcome "verde" com noop, a Issue não fecha — o invariante mora aqui, não no executor.
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = UUID.randomUUID();
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        IssueSettlement settlement = issues.settleAfterRun(companyId, issueId, runId,
                new RunOutcome(true, null, true));

        assertThat(settlement).isNotEqualTo(IssueSettlement.COMPLETED);
        assertThat(issues.get(companyId, issueId).getStatus()).isNotEqualTo(IssueStatus.DONE);
    }

    @Test
    void noopEmIssueDeAltoRiscoNaoAbreCicloDeReview() {
        // Um run que não fez nada não tem entregável a revisar: nem DONE nem PENDING_REVIEW —
        // vai direto ao park-for-human (BLOCKED), sem fabricar ciclo de review vazio (ADR-0012).
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa arriscada", null, 5).getId();
        UUID runId = UUID.randomUUID();
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        assertThat(issues.settleAfterRun(companyId, issueId, runId,
                RunOutcome.noop("run no-op: agente process sem runtime_command")))
                .isEqualTo(IssueSettlement.BLOCKED);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(countAction(companyId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW)).isEqualTo(0);
    }
}
