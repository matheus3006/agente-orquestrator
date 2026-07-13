package com.orquestrador.governance.approval;

import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.skills.SkillSetService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * O gate da (i) (F-AT1, grill agente-como-tool D4): recebe a saída de um Run de Issue, colhe os
 * pedidos {@code ```pedido-skill} via {@link SkillRequestScanner} e os entrega ao
 * {@link ApprovalService} — SEM alargar autonomia sem teto (D6 do grill motor-nativo): cap-por-run
 * de pedidos DISTINTOS ({@value #CAP_PEDIDOS_POR_RUN}), dedup contra o que o executor já tem
 * efetivamente nesta Issue (base ∪ anexos — {@link SkillSetService#resolveForRun}), e evento
 * VISÍVEL no StepTrace do Run quando algo é descartado (anti-silêncio, o padrão da casa). O
 * dedup contra PENDING já existe de graça no fingerprint do {@link ApprovalService#propose}.
 */
@Component
public class SkillRequestGate {

    static final int CAP_PEDIDOS_POR_RUN = 5;

    private final SkillRequestScanner scanner;
    private final ApprovalService approvals;
    private final SkillSetService skillSets;
    private final HeartbeatRunStore store;

    public SkillRequestGate(SkillRequestScanner scanner, ApprovalService approvals,
                            SkillSetService skillSets, HeartbeatRunStore store) {
        this.scanner = scanner;
        this.approvals = approvals;
        this.skillSets = skillSets;
        this.store = store;
    }

    public void handle(UUID companyId, UUID agentId, UUID issueId, UUID runId, String reply) {
        SkillRequestScanner.Scan scan = scanner.scan(reply);
        if (scan.pedidos().isEmpty() && scan.descartadas() == 0) {
            return;
        }

        Set<String> jaAtendidas = skillsJaEfetivasNaIssue(companyId, agentId, issueId);
        Set<String> distintas = new LinkedHashSet<>();
        for (SkillRequestScanner.SkillRequest pedido : scan.pedidos()) {
            if (!jaAtendidas.contains(pedido.skillKey())) {
                distintas.add(pedido.skillKey());
            }
        }

        List<String> aceitas = new ArrayList<>();
        List<String> estouradas = new ArrayList<>();
        for (String skillKey : distintas) {
            if (aceitas.size() < CAP_PEDIDOS_POR_RUN) {
                aceitas.add(skillKey);
            } else {
                estouradas.add(skillKey);
            }
        }

        for (String skillKey : aceitas) {
            approvals.propose(companyId, agentId, null, runId, new ProposalScanner.Proposal(
                    ApprovalActionType.SKILL_INSTALL_REQUEST,
                    "{\"issueId\":\"" + issueId + "\",\"skillKey\":\"" + skillKey + "\"}",
                    "Pedido de skill: " + skillKey, "skills"));
        }

        int malformadas = scan.descartadas();
        if (!estouradas.isEmpty() || malformadas > 0) {
            store.recordStep(runId, "SKILL_REQUEST_DISCARDED", descarte(estouradas, malformadas));
        }
    }

    /** O que o executor JÁ tem efetivamente nesta Issue (base ∪ anexos) — o cinto contra "instalado". */
    private Set<String> skillsJaEfetivasNaIssue(UUID companyId, UUID agentId, UUID issueId) {
        Set<String> nomes = new LinkedHashSet<>();
        skillSets.resolveForRun(companyId, agentId, issueId).forEach(version -> nomes.add(version.getName()));
        return nomes;
    }

    private static String descarte(List<String> estouradas, int malformadas) {
        StringBuilder texto = new StringBuilder();
        if (!estouradas.isEmpty()) {
            texto.append(estouradas.size()).append(" pedido(s) além do teto (")
                    .append(CAP_PEDIDOS_POR_RUN).append("/run): ").append(String.join(", ", estouradas));
        }
        if (malformadas > 0) {
            if (texto.length() > 0) {
                texto.append("; ");
            }
            texto.append(malformadas).append(" bloco(s) `pedido-skill` malformado(s)");
        }
        return texto.toString();
    }
}
