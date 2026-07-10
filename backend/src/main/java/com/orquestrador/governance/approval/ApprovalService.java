package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.lineage.SpawnLineageGuard;
import com.orquestrador.memory.CompanyMemoryConsolidator;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentHireService;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O gate PRÉ-HOC (ADR-0018): {@link #propose} registra a intenção do CEO como Approval PENDING
 * (dedupe por fingerprint — re-emissão do mesmo alvo não empilha); {@link #decide} é o commit do
 * dono com o grill do racional (ADR-0020) — APPROVE dispara a AÇÃO REAL com ator=aprovador,
 * REJECT arquiva; os dois fabricam uma Decisão (ADR-0019/D5) e direção consolida a carta.
 *
 * <p>O dispatch roda NA MESMA transação do commit: uma ação que falha (alvo malformado, líder
 * inexistente) desfaz a decisão inteira — a Approval segue PENDING, nunca meio-commitada
 * (deny-by-default).
 */
@Service
public class ApprovalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalRepository approvals;
    private final RacionalPolicy racionalPolicy;
    private final DecisionLog decisionLog;
    private final CompanyMemoryConsolidator consolidator;
    private final ActivityLogService activityLog;
    private final AgentHireService hires;
    private final IssueService issues;
    private final WakeupService wakeups;
    private final SpawnLineageGuard lineageGuard;

    public ApprovalService(ApprovalRepository approvals, RacionalPolicy racionalPolicy,
                           DecisionLog decisionLog, CompanyMemoryConsolidator consolidator,
                           ActivityLogService activityLog, AgentHireService hires,
                           IssueService issues, WakeupService wakeups,
                           SpawnLineageGuard lineageGuard) {
        this.approvals = approvals;
        this.racionalPolicy = racionalPolicy;
        this.decisionLog = decisionLog;
        this.consolidator = consolidator;
        this.activityLog = activityLog;
        this.hires = hires;
        this.issues = issues;
        this.wakeups = wakeups;
        this.lineageGuard = lineageGuard;
    }

    /**
     * A proposta vira PENDING — IDEMPOTENTE por (agente, ação, fingerprint do alvo): o mesmo
     * turno re-executado (reaper) ou o CEO repetindo a proposta devolve a MESMA linha, nunca
     * empilha fila pro board (o índice parcial da V17 é o backstop Postgres deste dedupe).
     */
    @Transactional
    public Approval propose(UUID companyId, UUID requestedByAgentId, UUID conversationId, UUID runId,
                            ProposalScanner.Proposal proposal) {
        String area = racionalPolicy.areaFor(proposal.actionType(), proposal.area());
        String fingerprint = fingerprintOf(proposal.targetDetailJson());
        Approval existing = approvals
                .findFirstByCompanyIdAndRequestedByAgentIdAndActionTypeAndTargetFingerprintAndStatus(
                        companyId, requestedByAgentId, proposal.actionType(), fingerprint,
                        ApprovalStatus.PENDING)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        Approval approval = approvals.save(new Approval(UUID.randomUUID(), companyId, requestedByAgentId,
                conversationId, runId, proposal.actionType(), proposal.targetDetailJson(), fingerprint,
                area, proposal.resumo(), racionalPolicy.deriveTipo(area)));
        activityLog.record(companyId, ActorType.AGENT, requestedByAgentId, ActivityAction.CEO_PROPOSED,
                "approval", approval.getId(), Map.of("runId", runId != null ? runId : ""));
        return approval;
    }

    /**
     * O commit do dono. O grill (ADR-0020) roda ANTES de qualquer efeito: racional ausente ou
     * "porque quis" → {@link DecideOutcome.Grilled} (rodada contada, Approval segue PENDING).
     * Racional presente → decide + dispara (APPROVE) + fabrica a Decisão (D5) + consolida a
     * carta se for direção.
     */
    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId) {
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId, null);
    }

    /**
     * O commit com a MÃO SOBERANA no motor (A4, adendo ADR-0031): no menu do hire as skills são
     * somente-leitura, mas o TRIO do motor é livremente editável pelo dono — o override vence o
     * motor protocolado no alvo, sem rejeitar nem devolver ao Planejador.
     */
    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride) {
        Approval approval = approvals.findByIdAndCompanyId(approvalId, companyId)
                .orElseThrow(() -> new NotFoundException("Approval nao encontrada: " + approvalId));
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new ConflictException("Approval ja decidida (" + approval.getStatus() + "): " + approvalId);
        }

        var probe = racionalPolicy.grillProbe(approval.getRacionalTipo(), racional);
        if (probe.isPresent()) {
            approval.registerGrillRound();
            approvals.save(approval);
            return new DecideOutcome.Grilled(probe.get(), approval.getGrillRounds());
        }

        approval.decide(decision, actorType, actorId, racional.strip(), reason);

        Map<String, Object> detail = new HashMap<>();
        detail.put("requestedByAgentId", approval.getRequestedByAgentId());
        if (decision == ApprovalDecision.APPROVE) {
            dispatchAction(approval, actorType, actorId, detail, engineOverride);
        }
        approvals.save(approval);

        ActivityAction action = decision == ApprovalDecision.APPROVE
                ? ActivityAction.APPROVAL_APPROVED : ActivityAction.APPROVAL_REJECTED;
        activityLog.record(companyId, actorType, actorId, action, "approval", approval.getId(), detail);

        recordGateDecision(approval, decision);
        return new DecideOutcome.Committed(approval);
    }

    @Transactional(readOnly = true)
    public List<Approval> queue(UUID companyId, ApprovalStatus status) {
        return approvals.findByCompanyIdAndStatusOrderByRequestedAt(companyId, status);
    }

    @Transactional(readOnly = true)
    public List<Approval> anchoredTo(UUID companyId, UUID conversationId) {
        return approvals.findByCompanyIdAndConversationIdOrderByRequestedAt(companyId, conversationId);
    }

    /** A fábrica de Decisões do gate (ADR-0019/D5): todo commit vira registro datado + endereçável. */
    private void recordGateDecision(Approval approval, ApprovalDecision decision) {
        DecisionTipo tipo = DecisionLog.tipoFor(approval.getArea());
        DecisionOrigem origem = decision == ApprovalDecision.APPROVE
                ? DecisionOrigem.PROPOSTA_CEO   // conteúdo do CEO, selado pelo dono
                : DecisionOrigem.DONO_NO_GATE;  // o dono barrou/redirecionou
        String oQueMudou = "[" + decision.name() + " " + approval.getActionType().name() + "] "
                + (approval.getResumo() != null ? approval.getResumo() : approval.getTargetDetail());
        Decision decisao = new Decision(UUID.randomUUID(), approval.getCompanyId(), approval.getArea(),
                tipo, origem, oQueMudou, approval.getRacional(), approval.getRacionalTipo(),
                approval.isRacionalArrancado())
                .anchoredToApproval(approval.getId());
        if (approval.getRacionalTipo() == RacionalTipo.INTUICAO) {
            decisao.withEvidencia(racionalPolicy.maieuticProbe()); // D10: acompanha, nunca bloqueia
        }
        decisionLog.record(decisao);
        if (tipo == DecisionTipo.DIRECAO) {
            consolidator.refresh(approval.getCompanyId()); // a carta é projeção — mesma tx do gate
        }
    }

    /** APPROVE dispara a ação REAL com ator=aprovador (ADR-0018); os IDs criados vão no detail. */
    private void dispatchAction(Approval approval, ActorType actorType, UUID actorId,
                                Map<String, Object> detail, EngineOverride engineOverride) {
        JsonNode alvo = parseAlvo(approval);
        switch (approval.getActionType()) {
            case HIRE_AGENT -> {
                String name = textOrNull(alvo, "name");
                String role = textOrNull(alvo, "role");
                if (name == null || role == null) {
                    throw new ConflictException("Proposta de hire sem name/role: " + approval.getId());
                }
                // Motor (A4): o alvo protocolado carrega o trio sugerido; o override do dono no
                // menu VENCE campo-a-campo (mão soberana — CEO tem viés de motor).
                EngineOverride doDono = engineOverride == null ? EngineOverride.none() : engineOverride;
                Agent hired = hires.hire(approval.getCompanyId(), new AgentHireService.HireCommand(
                                name, role, uuidOrNull(alvo, "reportsToId"),
                                preferNonBlank(doDono.adapterType(), textOrNull(alvo, "adapterType")),
                                null, null,
                                preferNonBlank(doDono.model(), textOrNull(alvo, "model")),
                                preferNonBlank(doDono.effort(), textOrNull(alvo, "effort")),
                                uuidOrNull(alvo, "roleTemplateVersionId"), approval.getRequestedByAgentId()),
                        actorType, actorId);
                detail.put("hiredAgentId", hired.getId());
            }
            case CREATE_ISSUE -> {
                String title = textOrNull(alvo, "title");
                if (title == null) {
                    throw new ConflictException("Proposta de issue sem title: " + approval.getId());
                }
                Issue issue = issues.create(approval.getCompanyId(), title,
                        textOrNull(alvo, "description"),
                        alvo.path("riskLevel").isInt() ? alvo.path("riskLevel").asInt() : null);
                detail.put("issueId", issue.getId());
            }
            case WAKEUP_AGENT -> {
                UUID agentId = uuidOrNull(alvo, "agentId");
                if (agentId == null) {
                    throw new ConflictException("Proposta de wakeup sem agentId: " + approval.getId());
                }
                // Residual T.4 decidido: o wakeup aprovado HERDA a linhagem do run do proponente —
                // linhagem é CAUSALIDADE, não autoria (o ator do activity_log segue = aprovador).
                // A aprovação humana não lava a cadeia: sem herança, o guard anti-loop seria letra
                // morta no v1 (todo wakeup agente-causado passa pelo gate por construção).
                var outcome = wakeups.enqueue(approval.getCompanyId(), agentId,
                        uuidOrNull(alvo, "issueId"), "approval:" + approval.getId(),
                        lineageGuard.spawnLineageOf(approval.getRunId(), approval.getRequestedByAgentId()));
                detail.put("runId", outcome.runId());
            }
        }
    }

    private static JsonNode parseAlvo(Approval approval) {
        try {
            return MAPPER.readTree(approval.getTargetDetail());
        } catch (Exception malformado) {
            throw new ConflictException("Alvo da proposta ilegivel: " + approval.getId());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalido) {
            throw new ConflictException("Campo " + field + " nao e um UUID valido na proposta");
        }
    }

    private static String fingerprintOf(String canonicalTarget) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(canonicalTarget.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossivel) {
            throw new IllegalStateException("MD5 indisponivel", impossivel);
        }
    }

    private static String preferNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /** O trio de motor editado pelo dono no menu (A4): campo em branco cai no alvo protocolado. */
    public record EngineOverride(String adapterType, String model, String effort) {
        static EngineOverride none() {
            return new EngineOverride(null, null, null);
        }
    }

    /** O resultado do decide: commit OU a pergunta do grill (o commit NÃO aconteceu). */
    public sealed interface DecideOutcome {
        record Committed(Approval approval) implements DecideOutcome {
        }

        record Grilled(String probe, int rounds) implements DecideOutcome {
        }
    }
}
