package com.orquestrador.planvalidation;

import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A superfície do PUSH (docs §2 passo 3, Opção A): os buracos factuais que o local achou no plano
 * voltam para o PRÓPRIO ARQUITETO no run seguinte da mesma issue, materializados na dieta pelo
 * {@code CompanyMemoryMaterializer} — o padrão PUSH da R19-A (feedback não é opcional; o agente
 * selado não tem canal inbound).
 *
 * <p><b>Seção PRÓPRIA, fora do Decision-log:</b> não toca Decisões nem consome {@code #N} (decisão
 * do grill) — o checklist é sinal de máquina, não decisão do dono.
 *
 * <p><b>Só BURACO entra.</b> "Presente" não é notícia e {@link Presence#INDETERMINATE} não é
 * buraco: empurrar um indeterminado seria o local inventando trabalho a partir da própria falha de
 * leitura — exatamente o falso negativo que esta fatia foi desenhada para não cometer.
 */
@Component
public class PlanChecklistSection {

    private final PlanValidationStore store;
    private final AgentRepository agents;
    private final PlanValidationProperties properties;

    public PlanChecklistSection(PlanValidationStore store, AgentRepository agents,
                                PlanValidationProperties properties) {
        this.store = store;
        this.agents = agents;
        this.properties = properties;
    }

    /** Vazio ⟹ nenhuma seção ⟹ a dieta do arquiteto fica exatamente como era antes desta fatia. */
    public Optional<String> render(UUID agentId, UUID issueId) {
        // O kill-switch GLOBAL vem primeiro de propósito: desligado (o default) não custa nem a
        // busca do agente — e este caminho roda no prep de TODO run de issue.
        if (properties.getMode() != PlanValidationProperties.Mode.PUSH
                || agentId == null || issueId == null || !pushLiberadoPara(agentId)) {
            return Optional.empty();
        }
        List<PlanCheckVerdict> buracos = store.latestForIssue(issueId).stream()
                .filter(PlanCheckVerdict::isHole)
                .toList();
        if (buracos.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        out.append("# Checklist do validador (confira ANTES de reentregar)\n\n");
        out.append("> Um verificador AUXILIAR de peso menor conferiu o plano do seu run anterior\n");
        out.append("> contra as checagens do seu molde e não ENCONTROU os itens abaixo. Ele é\n");
        out.append("> mecânico e pode errar: o julgamento é SEU. Se o buraco for real, conserte;\n");
        out.append("> se não for (ex.: um delete óbvio que dispensa arquivo:linha), siga em frente.\n\n");
        out.append("## Checklist do validador\n");
        for (PlanCheckVerdict buraco : buracos) {
            out.append(linha(buraco));
        }
        return Optional.of(out.toString());
    }

    /** Kill-switch: o GLOBAL (modo) e o POR-PAPEL, resolvidos no papel do agente daquele run. */
    private boolean pushLiberadoPara(UUID agentId) {
        return agents.findById(agentId)
                .map(Agent::getRole)
                .map(properties::pushEnabledFor)
                .orElse(false);
    }

    private static String linha(PlanCheckVerdict buraco) {
        String evidencia = buraco.evidence() == null || buraco.evidence().isBlank()
                ? "o validador não achou trecho correspondente"
                : buraco.evidence();
        return "- **" + buraco.checkId() + "** — não encontrado. O que o validador viu: \""
                + evidencia + "\"\n";
    }
}
