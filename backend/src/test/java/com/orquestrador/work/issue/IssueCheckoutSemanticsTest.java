package com.orquestrador.work.issue;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Semantica deterministica do checkout atomico (sem threads): cada ramo do WHERE guardado.
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueCheckoutSemanticsTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID companyId;
    private UUID agentA;
    private UUID agentB;

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newAgent(UUID company, String name) {
        return agents.save(new Agent(UUID.randomUUID(), company, name, "worker", null)).getId();
    }

    @BeforeEach
    void setup() {
        companyId = companies.create("C", "c-" + rand(), UUID.randomUUID()).getId();
        agentA = newAgent(companyId, "A");
        agentB = newAgent(companyId, "B");
    }

    @Test
    void cicloDeVidaDoCheckout() {
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID run = runReal(companyId, agentA, issueId);

        // A pega a issue OPEN
        CheckoutResult r1 = issues.checkout(companyId, issueId, agentA, run);
        assertThat(r1.acquired()).isTrue();
        assertThat(r1.issue().getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(r1.issue().getAssigneeAgentId()).isEqualTo(agentA);
        assertThat(r1.runId()).isEqualTo(run);

        // B nao pega (409): assignee ja e A
        assertThat(issues.checkout(companyId, issueId, agentB, runReal(companyId, agentB, issueId))
                .acquired()).isFalse();

        // A reentra com o MESMO run -> idempotente
        assertThat(issues.checkout(companyId, issueId, agentA, run).acquired()).isTrue();

        // A com run NOVO nao rouba a propria trava ativa (a 2a tentativa do mesmo agente)
        assertThat(issues.checkout(companyId, issueId, agentA, runReal(companyId, agentA, issueId))
                .acquired()).isFalse();

        // completar com run que NAO detem a trava falha — este é o guard do lock (tryComplete),
        // e ele segue valendo mesmo para um run com procedencia plena. As duas cercas são
        // independentes: uma diz QUEM pode agir em nome do run, a outra SE o run ainda detém.
        assertThat(issues.complete(companyId, issueId, runReal(companyId, agentA, issueId), agentA))
                .isFalse();

        // completar com o run detentor -> DONE e trava liberada
        assertThat(issues.complete(companyId, issueId, run, agentA)).isTrue();
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);

        // apos DONE, ninguem pega (status fora de OPEN/IN_PROGRESS)
        assertThat(issues.checkout(companyId, issueId, agentB, runReal(companyId, agentB, issueId))
                .acquired()).isFalse();
    }

    /**
     * A cerca de PROCEDÊNCIA (Fatia I-ter) roda ANTES do WHERE guardado, então um {@code runId} sem
     * linha nem chega ao {@code tryCheckout}/{@code tryComplete}. Aqui está a metade que o
     * {@code cicloDeVidaDoCheckout} não pode mais exercer, e que sumiria em silêncio se ninguém a
     * escrevesse: as duas rotas agent-facing recusam o runId inventado.
     */
    @Test
    void runIdSemLinhaNemChegaAoWhereGuardado() {
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        assertThatThrownBy(() -> issues.checkout(companyId, issueId, agentA, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> issues.complete(companyId, issueId, UUID.randomUUID(), agentA))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * O ramo {@code company_id} do WHERE guardado, isolado da cerca nova.
     *
     * <p>Para chegar até ele o {@code runId} precisa PASSAR pela procedência, então a linha de Run
     * é montada de propósito com a company do chamador e a issue alheia — um par que o motor real
     * nunca produz. É o preço de testar o WHERE sem que a cerca o intercepte antes; a recusa do
     * caso realista (sem linha nenhuma) está em {@link #runIdSemLinhaNemChegaAoWhereGuardado}.
     */
    @Test
    void checkoutComCompanyErradaNaoPega() {
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID otraCompany = companies.create("X", "x-" + rand(), UUID.randomUUID()).getId();
        UUID otroAgente = newAgent(otraCompany, "Z");

        // company_id no WHERE nao casa -> 0 linhas
        assertThat(issues.checkout(otraCompany, issueId, otroAgente, runReal(otraCompany, otroAgente, issueId))
                .acquired()).isFalse();
    }

    /** Um Run REAL do motor — sem a linha, a cerca de procedência recusa antes do WHERE. */
    private UUID runReal(UUID company, UUID agent, UUID issue) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), company, agent, issue, RunStatus.RUNNING))
                .getId();
    }
}
