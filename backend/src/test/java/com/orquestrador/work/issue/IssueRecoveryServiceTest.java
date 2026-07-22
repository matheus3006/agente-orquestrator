package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recovery de Issue travada por Run FAILED (ADR-0011): {@code settleAfterRun} estaciona em BLOCKED
 * (guardado pelo lock — a vítima de outro run fica intacta), e o board destrava com REOPEN (re-executa
 * mantendo o agente) ou CANCEL.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueRecoveryServiceTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private HeartbeatRunRepository runs;

    /**
     * Um Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter). O status é do
     * caso: um run ainda ATIVO (QUEUED/RUNNING/RECOVERING) prende o agente no coalescing do
     * {@code WakeupService}, e aí o REOPEN não conseguiria enfileirar a re-execução.
     */
    private UUID runReal(UUID companyId, UUID agentId, UUID issueId, RunStatus status) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, status)).getId();
    }

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        return agents.save(agent).getId();
    }

    private long countAction(UUID companyId, ActivityAction action) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entry -> entry.getAction().equals(action.name())).count();
    }

    @Test
    void falhaEstacionaEmBlockedComLockLiberadoEEvento() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = runReal(companyId, agentId, issueId, RunStatus.FAILED);
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();

        assertThat(issues.settleAfterRun(companyId, issueId, runId, RunOutcome.failure("boom")))
                .isEqualTo(IssueSettlement.BLOCKED);

        Issue blocked = issues.get(companyId, issueId);
        assertThat(blocked.getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(blocked.getExecutionLockRunId()).isNull();
        assertThat(blocked.getAssigneeAgentId()).isEqualTo(agentId); // assignee mantido p/ o REOPEN
        assertThat(countAction(companyId, ActivityAction.ISSUE_BLOCKED)).isEqualTo(1);
    }

    @Test
    void settleDeRunQueNaoDetinhaAIssueEhNoOpVitimaIntacta() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID owningRun = runReal(companyId, agentId, issueId, RunStatus.RUNNING);
        assertThat(issues.checkout(companyId, issueId, agentId, owningRun).acquired()).isTrue();

        // Um run intruso (que não detém a Issue) falha → NÃO pode bloquear a vítima.
        assertThat(issues.settleAfterRun(companyId, issueId, UUID.randomUUID(), RunOutcome.failure("boom")))
                .isEqualTo(IssueSettlement.NOT_SETTLED);

        Issue victim = issues.get(companyId, issueId);
        assertThat(victim.getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(victim.getExecutionLockRunId()).isEqualTo(owningRun);
        assertThat(countAction(companyId, ActivityAction.ISSUE_BLOCKED)).isEqualTo(0);
    }

    @Test
    void reopenVoltaParaOpenMantendoAgenteEReexecutaConcluindo() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId); // "echo ok" (sucede)
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID failedRun = runReal(companyId, agentId, issueId, RunStatus.FAILED);
        issues.checkout(companyId, issueId, agentId, failedRun);
        issues.settleAfterRun(companyId, issueId, failedRun, RunOutcome.failure("boom")); // → BLOCKED

        issues.unblock(companyId, issueId, UnblockDecision.REOPEN, ActorType.BOARD, UUID.randomUUID());
        Issue reopened = issues.get(companyId, issueId);
        assertThat(reopened.getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(reopened.getAssigneeAgentId()).isEqualTo(agentId);
        assertThat(countAction(companyId, ActivityAction.ISSUE_REOPENED)).isEqualTo(1);

        // Re-execução pelo caminho NORMAL de wakeup (mesmo agente) → conclui.
        wakeups.enqueue(companyId, agentId, issueId, "retentar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void cancelEncerraAIssue() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID failedRun = runReal(companyId, agentId, issueId, RunStatus.FAILED);
        issues.checkout(companyId, issueId, agentId, failedRun);
        issues.settleAfterRun(companyId, issueId, failedRun, RunOutcome.failure("boom"));

        issues.unblock(companyId, issueId, UnblockDecision.CANCEL, ActorType.BOARD, UUID.randomUUID());
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.CANCELLED);
        assertThat(countAction(companyId, ActivityAction.ISSUE_CANCELLED)).isEqualTo(1);
    }

    @Test
    void unblockEmIssueNaoBloqueadaFalha() {
        UUID companyId = newCompany();
        UUID issueId = issues.create(companyId, "tarefa", null).getId(); // OPEN, nunca bloqueada
        assertThatThrownBy(() -> issues.unblock(companyId, issueId, UnblockDecision.REOPEN,
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }
}
