package com.orquestrador.governance.budget;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.execution.LeaseReaper;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.Approval;
import com.orquestrador.governance.approval.ApprovalActionType;
import com.orquestrador.governance.approval.ApprovalDecision;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.governance.approval.ProposalScanner;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O LEDGER DE ORQUESTRAÇÃO (T.4; ADR-0016 rev. 2026-07-04): cada ato de orquestração
 * (AGENT_SPAWN / WAKEUP / RUN_OPEN) é COBRADO NA ABERTURA como evento no ledger de budget (V5) —
 * o teto de orquestração é ORÇAMENTO, não contador solto. Moeda SEPARADA do dólar: as somas de
 * gasto em dólar nunca veem centavos de orquestração (e vice-versa). A conta cai em quem CAUSOU
 * (último elo da linhagem; nulo = ato humano direto).
 */
@SpringBootTest
@ActiveProfiles("test")
class OrchestrationLedgerTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private LeaseReaper reaper;
    @Autowired
    private CostEventRepository costs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private TeamService times;

    @AfterEach
    void drenaAFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // a classe enfileira sem despachar — o contexto H2 é compartilhado entre classes
        }
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void wakeupEhCobradoNaAberturaEmMoedaSeparadaDoDolar() {
        UUID companyId = novaCompany();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        wakeups.enqueue(companyId, agentId, null, "trabalhar");

        // o ato entrou no ledger na moeda de orquestração…
        assertThat(budgets.orchestrationSpentForCompany(companyId)).isEqualTo(10);
        // …e o teto de DÓLAR não vê um centavo dele (moedas separadas)
        assertThat(budgets.spentForCompany(companyId)).isZero();

        // a conta registra o CAUSADOR: wakeup de mão humana direta = sem agente na conta
        List<CostEvent> atos = costs.findByCompanyIdAndOrchestrationAction(
                companyId, OrchestrationAction.WAKEUP);
        assertThat(atos).hasSize(1);
        assertThat(atos.get(0).getAgentId()).isNull();
    }

    @Test
    void runOpenEhCobradoUmaVezSoMesmoComReExecucaoPeloReaper() {
        UUID companyId = novaCompany();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "B", "worker", null)).getId();

        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId(); // WAKEUP = 10

        // abertura de verdade: o claim cobra o RUN_OPEN
        assertThat(store.claim(runId, "node-morto", java.time.Duration.ofMillis(-1))).isTrue();
        assertThat(budgets.orchestrationSpentForCompany(companyId)).isEqualTo(11);

        // reaper devolve pra fila; a RE-execução idempotente NÃO cobra o ato de novo
        reaper.reapOnce();
        assertThat(store.claim(runId, "node-vivo", java.time.Duration.ofMinutes(2))).isTrue();
        assertThat(budgets.orchestrationSpentForCompany(companyId)).isEqualTo(11);

        store.finishSuccess(runId, "node-vivo");
    }

    @Test
    void hireAprovadoCobraAgentSpawnNaContaDoProponente() {
        UUID companyId = novaCompany();
        UUID ceoId = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null)).getId();

        // O alvo do hire carrega o TIME por NOME (ADR-0045) e ele precisa existir antes do approve.
        times.create(companyId, "Dev", "backend", null);
        ProposalScanner.Proposal contratacao = new ProposalScanner.Proposal(
                ApprovalActionType.HIRE_AGENT,
                "{\"name\":\"Novo\",\"role\":\"executor\",\"dominio\":\"Dev\",\"foco\":\"backend\"}",
                "Contratar executor", null);
        Approval proposta = approvals.propose(companyId, ceoId, null, null, contratacao);
        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "o roadmap tem tasks de execucao sem dono", null, ActorType.BOARD, UUID.randomUUID());

        // o ato de SPAWNAR cai na conta de quem CAUSOU (o CEO proponente), não do aprovador
        List<CostEvent> spawns = costs.findByCompanyIdAndOrchestrationAction(
                companyId, OrchestrationAction.AGENT_SPAWN);
        assertThat(spawns).hasSize(1);
        assertThat(spawns.get(0).getAgentId()).isEqualTo(ceoId);
        assertThat(budgets.orchestrationSpentForAgent(ceoId)).isEqualTo(100);
    }

    @Test
    void tetoDeOrquestracaoEstouradoAbreIncidenteEPausaOEscopoSemExecutarNada() {
        // Drena a fila global ANTES: o ==0 do fim tem que ser SÓ desta company (contexto H2
        // compartilhado entre classes — o gotcha do BudgetPreflightTest).
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
        UUID companyId = novaCompany();
        UUID a1 = agents.save(new Agent(UUID.randomUUID(), companyId, "A1", "worker", null)).getId();
        UUID a2 = agents.save(new Agent(UUID.randomUUID(), companyId, "A2", "worker", null)).getId();
        UUID a3 = agents.save(new Agent(UUID.randomUUID(), companyId, "A3", "worker", null)).getId();
        // teto de ORQUESTRAÇÃO da company: 25 (dois wakeups cabem; o terceiro cruza).
        // limitCents=0 = teto de dólar inerte (guard >0) — a policy é só de orquestração.
        budgets.createPolicy(companyId, ScopeType.COMPANY, companyId, 0, null, 25L);

        wakeups.enqueue(companyId, a1, null, "um");   // 10
        wakeups.enqueue(companyId, a2, null, "dois"); // 20
        assertThat(budgets.invocationBlocked(companyId, null)).isFalse();

        wakeups.enqueue(companyId, a3, null, "tres"); // 30 >= 25 → cruza NA ABERTURA

        assertThat(budgets.invocationBlocked(companyId, null)).isTrue();
        assertThat(budgets.incidentsFor(companyId)).hasSize(1);

        // nada do escopo executa: o que sobrou na fila morre no preflight (drena até o checkpoint)
        assertThat(dispatcher.dispatchOnce()).isZero();
    }
}
