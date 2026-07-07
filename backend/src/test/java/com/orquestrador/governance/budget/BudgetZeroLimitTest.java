package com.orquestrador.governance.budget;

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
 * Regressão do achado da revisão adversarial da F.7 (fatia B expôs este latente): com o roteamento de
 * `CostEvent(costCents=0)` do adapter, uma policy degenerada de `limitCents=0` fazia `spent(0) >= limit(0)`
 * abrir um incidente ESPÚRIO na primeira emissão de tokens e cancelar TODO o escopo — violando o invariante
 * "costCents=0 é inócuo" (ADR-0006/0014 Q7). O guard `limitCents > 0` no enforce restaura o invariante mesmo
 * para uma policy 0 pré-existente (edge/dado antigo), independente da validação de borda.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetZeroLimitTest {

    @Autowired
    private BudgetService budgets;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void policyComLimiteZeroNaoAbreIncidenteComEmissaoDeTokensCustoZero() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();

        // policy degenerada limite 0 (criável direto no serviço — simula dado antigo/edge sem passar pela borda)
        budgets.createPolicy(companyId, ScopeType.AGENT, agentId, 0, null);

        // emissão INÓCUA do adapter: costCents=0 com tokens reais (o caminho novo da fatia B)
        budgets.recordCost(companyId, agentId, null, null, "anthropic", "opus", 0L, 1500, 800);

        // não abre incidente espúrio nem cancela o escopo; o ledger em dólares fica 0
        assertThat(budgets.incidentsFor(companyId)).isEmpty();
        assertThat(budgets.spentForAgent(agentId)).isZero();
    }
}
