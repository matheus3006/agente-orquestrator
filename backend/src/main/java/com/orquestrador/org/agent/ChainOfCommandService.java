package com.orquestrador.org.agent;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A CADEIA DE COMANDO (ADR-0017, T.2 — "ligar o reports_to"): reparent pendura o agente num novo
 * líder direto (nulo = vira raiz). A árvore proíbe SÓ ciclo — nunca QUEM reporta (residual 5 do
 * fechado): o guard sobe a cadeia a partir do novo líder e recusa se passar pelo próprio agente
 * (self incluso). Visited-set no walk: dado pré-corrompido com ciclo não trava a operação.
 * Profundidade fica livre no v1 (o cap de profundidade é do spawn_lineage, T.4).
 */
@Service
public class ChainOfCommandService {

    private final AgentRepository agents;

    public ChainOfCommandService(AgentRepository agents) {
        this.agents = agents;
    }

    @Transactional
    public Agent reparent(UUID companyId, UUID agentId, UUID novoLiderId) {
        Agent agente = agenteDaCompany(companyId, agentId);
        if (novoLiderId != null) {
            if (novoLiderId.equals(agentId)) {
                throw new ConflictException("agente nao pode reportar a si mesmo");
            }
            Agent novoLider = agenteDaCompany(companyId, novoLiderId);
            assertNaoFechaCiclo(agente, novoLider);
        }
        agente.reassignReportsTo(novoLiderId);
        return agents.save(agente);
    }

    /** Sobe a cadeia a partir do novo líder: se passar pelo agente, o reparent fecharia um ciclo. */
    private void assertNaoFechaCiclo(Agent agente, Agent novoLider) {
        Set<UUID> visitados = new HashSet<>();
        UUID cursor = novoLider.getId();
        while (cursor != null && visitados.add(cursor)) {
            if (cursor.equals(agente.getId())) {
                throw new ConflictException("reparent fecharia um ciclo na cadeia de comando: "
                        + novoLider.getName() + " ja esta abaixo de " + agente.getName());
            }
            cursor = agents.findById(cursor).map(Agent::getReportsToId).orElse(null);
        }
    }

    private Agent agenteDaCompany(UUID companyId, UUID agentId) {
        return agents.findById(agentId)
                .filter(candidate -> candidate.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado: " + agentId));
    }
}
