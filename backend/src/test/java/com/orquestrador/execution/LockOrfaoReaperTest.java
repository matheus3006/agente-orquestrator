package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O REAPER da coluna {@code execution_lock_run_id} (Fatia I, peça 2 — a REDE).
 *
 * <p>A rede existe porque nem todo produtor de lock órfão tem conserto na fonte: o drain em lote do
 * hard-stop cancela por UPDATE em massa (sem {@code runId} individual na mão), e o próprio
 * {@code settleAfterRun} pode LANÇAR no meio (banco fora) — aí o run pousa {@code FAILED} com o
 * carimbo de pé. Uma varredura não distingue órfão novo de órfão antigo, então ela cobre também o
 * passivo já acumulado.
 *
 * <p><b>Os controles negativos são obrigatórios, não decoração.</b> Um reaper que colhe "tudo"
 * passaria nos três primeiros casos e arrancaria issues de runs vivos. {@code INTERRUPTED} fica de
 * fora <b>de propósito</b> (o kill do interrupt é best-effort — o processo pode estar vivo e ainda
 * vai escrever), e {@code RUNNING} é o caso normal de trabalho em voo.
 */
@SpringBootTest
@ActiveProfiles("test")
class LockOrfaoReaperTest {

    @Autowired
    private LockOrfaoReaper reaper;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void colheIssueCarimbadaPorRunEmStatusTerminal() {
        Carimbada cancelado = carimbar(RunStatus.CANCELLED);
        Carimbada falhado = carimbar(RunStatus.FAILED);
        Carimbada sucedido = carimbar(RunStatus.SUCCEEDED);

        reaper.reapOnce();

        assertThat(recarregar(cancelado).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(falhado).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(sucedido).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(cancelado).getExecutionLockRunId()).isNull();
    }

    @Test
    void naoColheIssueDeRunVivoNemDeRunInterrompido() {
        Carimbada rodando = carimbar(RunStatus.RUNNING);
        Carimbada interrompido = carimbar(RunStatus.INTERRUPTED);

        reaper.reapOnce();

        assertThat(recarregar(interrompido).getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(recarregar(interrompido).getExecutionLockRunId()).isEqualTo(interrompido.runId());
        assertThat(recarregar(rodando).getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(recarregar(rodando).getExecutionLockRunId()).isEqualTo(rodando.runId());
    }

    private record Carimbada(UUID companyId, UUID issueId, UUID runId) {
    }

    /** Uma Issue IN_PROGRESS carimbada com um run no status dado — o estado do órfão. */
    private Carimbada carimbar(RunStatus status) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa " + status, null).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, status)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        return new Carimbada(companyId, issueId, runId);
    }

    private Issue recarregar(Carimbada carimbada) {
        return issues.get(carimbada.companyId(), carimbada.issueId());
    }
}
