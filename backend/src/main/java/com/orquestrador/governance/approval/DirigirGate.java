package com.orquestrador.governance.approval;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.ChainOfCommandService;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * O gate do DIRIGIR (ADR-0047, D-DSG-1/2/8; molde F-AT1 = {@link SkillRequestGate}): recebe a
 * saída de um Run, colhe os blocos {@code ```dirigir```} via {@link DirigirScanner} e roteia pelo
 * {@code gestor_status} do braço — o gestor nem precisa saber o próprio status.
 *
 * <p>O GUARD DE BRAÇO (D-DSG-2) roda AQUI, no momento do ATO, 3 condições: (a) o ator É gestor
 * titular de ao menos um time; (b) o time do agente-alvo pertence à SUBÁRVORE de um time que ele
 * gere (braço = subárvore inteira, D-ORG-3); (c) o {@code gestor_status} DESSE braço decide o
 * ramo — PROMOVIDO → Approval nasce-APPROVED + issue direto ({@code dirigirIssueSemGate});
 * ESTÁGIO → {@code propose()} normal (PENDING, lote no gate — o dono vê COMO ele dirige antes de
 * confiar). O limite de braço vale nos DOIS ramos. Alvo inexistente/ambíguo/fora do braço =
 * fail-soft: descarte com evento VISÍVEL no StepTrace (anti-silêncio, padrão da casa), nada
 * criado — fora do braço é REJEITADO, não "ambíguo" (D-ORG-3). CAP por run de ordens aceitas
 * ({@value #CAP_ORDENS_POR_RUN}, o padrão do {@code SkillRequestGate}).
 *
 * <p>O alvo é endereçado por NOME e resolvido pra UUID AQUI (momento do ato) — o
 * {@code targetDetail} canônico carrega nome+id+titulo+risco+racional, e é sobre ele que o
 * fingerprint de dedupe é calculado (idêntico nos dois ramos: o mesmo bloco tem a mesma
 * identidade independente do status do gestor).
 */
@Component
public class DirigirGate {

    static final int CAP_ORDENS_POR_RUN = 5;

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final DirigirScanner scanner;
    private final ApprovalService approvals;
    private final TeamRepository teams;
    private final AgentRepository agents;
    private final ChainOfCommandService chain;
    private final HeartbeatRunStore store;

    public DirigirGate(DirigirScanner scanner, ApprovalService approvals, TeamRepository teams,
                       AgentRepository agents, ChainOfCommandService chain, HeartbeatRunStore store) {
        this.scanner = scanner;
        this.approvals = approvals;
        this.teams = teams;
        this.agents = agents;
        this.chain = chain;
        this.store = store;
    }

    public void handle(UUID companyId, UUID agentId, UUID runId, String reply) {
        DirigirScanner.Scan scan = scanner.scan(reply);
        if (scan.ordens().isEmpty() && scan.descartadas() == 0) {
            return;
        }
        List<String> avisos = new ArrayList<>();
        if (scan.descartadas() > 0) {
            avisos.add(scan.descartadas() + " bloco(s) `dirigir` malformado(s) ou sem racional (obrigatório)");
        }

        List<Team> bracos = teams.findByCompanyIdAndGestorAgentId(companyId, agentId);
        int aceitas = 0;
        for (DirigirScanner.Ordem ordem : scan.ordens()) {
            if (aceitas >= CAP_ORDENS_POR_RUN) {
                avisos.add("'" + ordem.titulo() + "' além do teto (" + CAP_ORDENS_POR_RUN + "/run)");
                continue;
            }
            if (bracos.isEmpty()) {
                avisos.add("'" + ordem.titulo() + "' descartada: o agente não é gestor titular de nenhum time");
                continue;
            }
            List<Agent> candidatos = agents.findByCompanyIdAndNameIgnoreCase(companyId, ordem.alvo());
            if (candidatos.isEmpty()) {
                avisos.add("alvo '" + ordem.alvo() + "' não existe no quadro — nada criado");
                continue;
            }
            if (candidatos.size() > 1) {
                avisos.add("alvo '" + ordem.alvo() + "' é AMBÍGUO (" + candidatos.size()
                        + " agentes com esse nome) — nada criado");
                continue;
            }
            Agent alvo = candidatos.get(0);
            // O braço é a SUBÁRVORE (D-ORG-3): o primeiro time gerido cuja subárvore contém o
            // time do alvo decide o ramo. Fora de todas = fora do braço, REJEITADO (não ambíguo).
            Team braco = alvo.getTeamId() == null ? null : bracos.stream()
                    .filter(time -> chain.pertenceASubarvore(time.getId(), alvo.getTeamId()))
                    .findFirst().orElse(null);
            if (braco == null) {
                avisos.add("alvo '" + ordem.alvo() + "' está FORA do braço do gestor (D-ORG-3) — nada criado");
                continue;
            }
            aceitas++;
            String targetDetail = targetDetailDe(ordem, alvo);
            String resumo = "dirigir a " + alvo.getName() + ": " + ordem.titulo();
            if (braco.gestorPromovido()) {
                approvals.dirigirIssueSemGate(companyId, agentId, runId,
                        new ApprovalService.OrdemDirigida(alvo.getId(), alvo.getTeamId(), braco.getId(),
                                ordem.titulo(), ordem.descricao(), ordem.risco(), ordem.racional(),
                                targetDetail, resumo));
            } else {
                // D-DSG-8: ESTÁGIO dirige EM LOTE no gate — propose() normal (dedupe de PENDING
                // por fingerprint de graça; ADR-0035 agrupa e o dono aprova N de uma vez).
                approvals.propose(companyId, agentId, null, runId, new ProposalScanner.Proposal(
                        ApprovalActionType.DIRECT_ISSUE, targetDetail, resumo, null));
            }
        }

        if (!avisos.isEmpty()) {
            store.recordStep(runId, "DIRIGIR_DISCARDED", String.join("; ", avisos));
        }
    }

    /**
     * O alvo CANÔNICO (chaves ordenadas — a identidade do fingerprint, como no
     * {@code ProposalScanner}): nome+id do dirigido, titulo/descricao/risco da issue e o racional
     * da gestora (obrigatório, D-DSG-4 — no ramo ESTÁGIO é aqui que o dono o lê no cartão).
     */
    private static String targetDetailDe(DirigirScanner.Ordem ordem, Agent alvo) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("agenteNome", alvo.getName());
        node.put("targetAgentId", alvo.getId().toString());
        node.put("titulo", ordem.titulo());
        if (ordem.descricao() != null) {
            node.put("descricao", ordem.descricao());
        }
        node.put("risco", ordem.risco());
        node.put("racional", ordem.racional());
        try {
            return CANONICAL.writeValueAsString(CANONICAL.treeToValue(node, Object.class));
        } catch (Exception impossivel) {
            throw new IllegalStateException("alvo do dirigir não serializável", impossivel);
        }
    }
}
