package com.orquestrador.governance.approval;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.lineage.SpawnLineage;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Residual do grilling T.4 DECIDIDO: WAKEUP_AGENT aprovado HERDA a linhagem do run do CEO
 * proponente (linhagem é CAUSALIDADE, não autoria — o ator no activity_log segue = aprovador,
 * ADR-0018). Se a aprovação abrisse linhagem raiz, o guard nasceria letra morta no v1: TODO
 * wakeup agente-causado passa pelo gate humano por construção — e um clique não pode lavar a
 * cadeia. Raiz nova = só mão soberana DIRETA do operador.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalWakeupLineageTest {

    @Autowired
    private ApprovalService approvals;
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

    @AfterEach
    void drenaAFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // a classe enfileira sem despachar — o contexto H2 é compartilhado entre classes
        }
    }

    @Test
    void wakeupAprovadoHerdaALinhagemDoRunDoProponente() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent ceo = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));
        Agent alvo = agents.save(new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null));

        // o run do CEO que emitiu a proposta (a âncora causal)
        UUID runDoCeo = wakeups.enqueue(companyId, ceo.getId(), null, "turno do ceo").runId();

        ProposalScanner.Proposal wakeupProposto = new ProposalScanner.Proposal(
                ApprovalActionType.WAKEUP_AGENT, "{\"agentId\":\"" + alvo.getId() + "\"}",
                "Acordar Echo", null);
        Approval proposta = approvals.propose(companyId, ceo.getId(), null, runDoCeo, wakeupProposto);
        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "a fila do Echo esta vazia e ha trabalho pronto", null, ActorType.BOARD, UUID.randomUUID());

        HeartbeatRun runDoAlvo = runs.findByAgentAndStatuses(alvo.getId(),
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING)).get(0);
        SpawnLineage herdada = SpawnLineage.decode(runDoAlvo.getSpawnLineage());
        assertThat(herdada.causer()).isEqualTo(ceo.getId());
    }

    @Test
    void wakeupAprovadoQueAcordariaOProprioProponenteEhRecusadoESeguePendente() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent ceo = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null));

        UUID runDoCeo = wakeups.enqueue(companyId, ceo.getId(), null, "turno do ceo").runId();

        ProposalScanner.Proposal autoWakeup = new ProposalScanner.Proposal(
                ApprovalActionType.WAKEUP_AGENT, "{\"agentId\":\"" + ceo.getId() + "\"}",
                "Acordar a si mesmo", null);
        Approval proposta = approvals.propose(companyId, ceo.getId(), null, runDoCeo, autoWakeup);

        // o loop CEO→CEO morre no dispatch da decisão: 409 e a Approval NUNCA fica meio-commitada
        assertThatThrownBy(() -> approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "quero que ele continue o trabalho agora", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("linhagem");
        assertThat(approvals.queue(companyId, ApprovalStatus.PENDING))
                .extracting(Approval::getId)
                .contains(proposta.getId());
    }
}
