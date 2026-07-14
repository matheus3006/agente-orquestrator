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
import com.orquestrador.memory.DecisionGrau;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.GateDecisionFactory;
import com.orquestrador.memory.GateDecisionFactory.Anchor;
import com.orquestrador.memory.GateDecisionFactory.GatePassage;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentHireService;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.roletemplate.RoleTemplate;
import com.orquestrador.org.roletemplate.RoleTemplateService;
import com.orquestrador.org.roletemplate.TipoFuncao;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import com.orquestrador.skills.IssueSkillAttachment;
import com.orquestrador.skills.Skill;
import com.orquestrador.skills.SkillRepository;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.work.issue.AbsorcaoDeIssueRouter;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
    private final GateDecisionFactory gateDecisions;
    private final ActivityLogService activityLog;
    private final AgentHireService hires;
    private final IssueService issues;
    private final WakeupService wakeups;
    private final SpawnLineageGuard lineageGuard;
    private final TeamRepository teams;
    private final AgentRepository agents;
    private final TeamService teamService;
    private final AbsorcaoDeIssueRouter absorcoes;
    private final RoleTemplateService roleTemplates;
    private final SkillRepository skills;
    private final SkillSetService skillSets;

    public ApprovalService(ApprovalRepository approvals, RacionalPolicy racionalPolicy,
                           DecisionLog decisionLog, GateDecisionFactory gateDecisions,
                           ActivityLogService activityLog, AgentHireService hires,
                           IssueService issues, WakeupService wakeups,
                           SpawnLineageGuard lineageGuard, TeamRepository teams,
                           AgentRepository agents, TeamService teamService,
                           AbsorcaoDeIssueRouter absorcoes, RoleTemplateService roleTemplates,
                           SkillRepository skills, SkillSetService skillSets) {
        this.approvals = approvals;
        this.racionalPolicy = racionalPolicy;
        this.decisionLog = decisionLog;
        this.gateDecisions = gateDecisions;
        this.activityLog = activityLog;
        this.hires = hires;
        this.issues = issues;
        this.wakeups = wakeups;
        this.lineageGuard = lineageGuard;
        this.teams = teams;
        this.agents = agents;
        this.teamService = teamService;
        this.absorcoes = absorcoes;
        this.roleTemplates = roleTemplates;
        this.skills = skills;
        this.skillSets = skillSets;
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
        // F-AT1 (grill agente-como-tool): o pedido de skill do EXECUTOR não é uma proposta do CEO —
        // audita sob o próprio nome (D5), mesmo passando pelo MESMO propose()/dedupe/fingerprint.
        ActivityAction auditado = proposal.actionType() == ApprovalActionType.SKILL_INSTALL_REQUEST
                ? ActivityAction.SKILL_INSTALL_REQUESTED
                : ActivityAction.CEO_PROPOSED;
        activityLog.record(companyId, ActorType.AGENT, requestedByAgentId, auditado,
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
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId, null, null);
    }

    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride) {
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId,
                engineOverride, null);
    }

    /**
     * O commit com a MÃO SOBERANA no motor (A4, adendo ADR-0031): no menu do hire as skills são
     * somente-leitura, mas o TRIO do motor é livremente editável pelo dono — o override vence o
     * motor protocolado no alvo, sem rejeitar nem devolver ao Planejador.
     *
     * <p>{@code supersedesSeq} (opcional) é a linhagem D11 na porta pré-hoc: o CEO propõe o link
     * citando o {@code #N}, o dono sela aqui — a Decisão fabricada aponta a supersedida e a carta
     * se auto-cura (o leitor já filtra). {@code #N} inexistente → 409 acionável, nada commitado.
     */
    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride, Integer supersedesSeq) {
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
        // A linhagem resolve ANTES de qualquer efeito: #N fantasma → 409 e a Approval segue PENDING.
        UUID supersedesId = supersedesSeq != null
                ? decisionLog.idDoEndereco(companyId, supersedesSeq) : null;

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

        fabricaDecisaoDoGate(approval, decision, supersedesId);
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

    /**
     * A porta pré-hoc como adapter FINO da fábrica única (C15 — a fiação de fabricar mora na
     * {@link GateDecisionFactory}, nunca aqui): monta a passagem e entrega. O racional já passou
     * pelo grill (presença garantida) — a Decisão desta porta sempre nasce.
     */
    private void fabricaDecisaoDoGate(Approval approval, ApprovalDecision decision, UUID supersedesId) {
        DecisionOrigem origem = decision == ApprovalDecision.APPROVE
                ? DecisionOrigem.PROPOSTA_CEO   // conteúdo do CEO, selado pelo dono
                : DecisionOrigem.DONO_NO_GATE;  // o dono barrou/redirecionou
        String oQueMudou = "[" + decision.name() + " " + approval.getActionType().name() + "] "
                + (approval.getResumo() != null ? approval.getResumo() : approval.getTargetDetail());
        gateDecisions.fabricate(new GatePassage(approval.getCompanyId(), approval.getArea(), origem,
                oQueMudou, approval.getRacional(), approval.getRacionalTipo(),
                approval.isRacionalArrancado(), supersedesId, DecisionGrau.DECISAO,
                new Anchor.NaApproval(approval.getId())));
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
                // ADR-0037 (D2): a lacuna histórica fechada — o alvo carrega o TIME e a issue
                // nasce com assignee = arquiteto ("nada que o CEO cria nasce sem dono").
                // Corrente quebrada → 409 acionável; conserto = reject + nova proposta, ou o
                // dono cria a issue no board (cai na absorção, D1).
                Agent arquiteto = arquitetoDoTimeAlvo(approval, alvo);
                Issue issue = issues.createAtribuida(approval.getCompanyId(), title,
                        textOrNull(alvo, "description"),
                        alvo.path("riskLevel").isInt() ? alvo.path("riskLevel").asInt() : null,
                        arquiteto.getId());
                detail.put("issueId", issue.getId());
                detail.put("assigneeAgentId", arquiteto.getId());
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
            case CREATE_TEAM -> {
                // ADR-0037 (D7): time + ARQUITETO atomicamente — o time do gate nasce com
                // arquiteto por construção (o invariante do dono, §G.1, honrado no caminho NOVO).
                String dominio = textOrNull(alvo, "dominio");
                String foco = textOrNull(alvo, "foco");
                JsonNode arquitetoAlvo = alvo.path("arquiteto");
                String nomeArquiteto = textOrNull(arquitetoAlvo, "name");
                String roleArquiteto = textOrNull(arquitetoAlvo, "role");
                if (dominio == null || foco == null || nomeArquiteto == null || roleArquiteto == null) {
                    throw new ConflictException("Proposta de time sem dominio/foco ou sem o"
                            + " arquiteto (name/role) — todo time nasce com arquiteto (ADR-0037): "
                            + approval.getId());
                }
                Team time = teamService.create(approval.getCompanyId(), dominio, foco);
                // O trio de motor do arquiteto respeita a mão soberana do dono no menu (A4).
                EngineOverride doDono = engineOverride == null ? EngineOverride.none() : engineOverride;
                Agent arquiteto = hires.hire(approval.getCompanyId(), new AgentHireService.HireCommand(
                                nomeArquiteto, roleArquiteto, null,
                                preferNonBlank(doDono.adapterType(), textOrNull(arquitetoAlvo, "adapterType")),
                                null, null,
                                preferNonBlank(doDono.model(), textOrNull(arquitetoAlvo, "model")),
                                preferNonBlank(doDono.effort(), textOrNull(arquitetoAlvo, "effort")),
                                uuidOrNull(arquitetoAlvo, "roleTemplateVersionId"),
                                approval.getRequestedByAgentId()),
                        actorType, actorId);
                teamService.assignAgent(approval.getCompanyId(), arquiteto.getId(), time.getId());
                teamService.setArquiteto(approval.getCompanyId(), time.getId(), arquiteto.getId());
                detail.put("teamId", time.getId());
                detail.put("hiredAgentId", arquiteto.getId());
                // Retoma automática (D7): o card nasceu ancorado na conversa da issue → o
                // Planejador recebe o bastão "o time existe; retome a #N" na MESMA tx.
                if (approval.getConversationId() != null) {
                    absorcoes.retomarAposCreateTeam(approval.getCompanyId(),
                            approval.getConversationId(), time);
                }
            }
            case CREATE_ROLE_TEMPLATE -> {
                // R10-A/D1-D3 (propose-then-commit de molde, ADR-0017 emendada): o molde é GLOBAL —
                // o CEO PROPÔS, o dono APROVOU. A versão nasce só aqui (nada persiste enquanto PENDING,
                // teto G5) com proposedBy=CEO (o proponente) / approvedBy=dono (o aprovador). Só CRIA
                // (G6: editar molde existente segue mão-soberana-direta na tela). Elo vazio = 409
                // acionável na tx do decide (segue PENDING).
                TipoFuncao tipoFuncao = tipoFuncaoDoAlvo(approval, alvo);
                String roleEspecifica = textOrNull(alvo, "roleEspecifica");
                if (roleEspecifica == null) {
                    throw new ConflictException("Proposta de molde sem roleEspecifica: " + approval.getId());
                }
                RoleTemplateService.Preset preset = new RoleTemplateService.Preset(
                        textOrNull(alvo, "adapterType"), textOrNull(alvo, "model"),
                        textOrNull(alvo, "effort"), stringListOf(alvo, "skillKeys"),
                        textOrNull(alvo, "reportsToDefault"));
                RoleTemplate molde = roleTemplates.createTemplate(tipoFuncao, roleEspecifica, preset,
                        textOrNull(alvo, "changeDescription"),
                        approval.getRequestedByAgentId(), actorId);
                detail.put("roleTemplateId", molde.getId());
            }
            case SKILL_INSTALL_REQUEST -> {
                // F-AT1 (grill agente-como-tool, D2/D3): reuso PURO do install-on-attach (ADR-0003)
                // — o AVAILABLE já instala do catálogo global na MESMA tx do attach. O agente que
                // RECEBE é sempre quem PEDIU (requestedByAgentId), nunca o aprovador (ator≠alvo).
                String skillKey = textOrNull(alvo, "skillKey");
                UUID issueId = uuidOrNull(alvo, "issueId");
                if (skillKey == null || issueId == null) {
                    throw new ConflictException("Pedido de skill sem skillKey/issueId: " + approval.getId());
                }
                Skill skill = skills.findBySkillKey(skillKey)
                        .orElseThrow(() -> new ConflictException("Skill '" + skillKey
                                + "' nao existe mais no catalogo global — rejeite e peça reemissao: "
                                + approval.getId()));
                IssueSkillAttachment attachment = skillSets.attachToIssue(approval.getCompanyId(), issueId,
                        approval.getRequestedByAgentId(), skill.getId(), actorType, actorId, approval.getRunId());
                detail.put("issueSkillAttachmentId", attachment.getId());
            }
        }
    }

    /** O tipo de função do molde proposto (enum FIXO); ausente/inválido = 409 acionável (segue PENDING). */
    private static TipoFuncao tipoFuncaoDoAlvo(Approval approval, JsonNode alvo) {
        String text = textOrNull(alvo, "tipoFuncao");
        if (text == null) {
            throw new ConflictException("Proposta de molde sem tipoFuncao: " + approval.getId());
        }
        try {
            return TipoFuncao.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalido) {
            throw new ConflictException("tipoFuncao invalido na proposta de molde (" + text + "): "
                    + approval.getId());
        }
    }

    /** Um array JSON de strings do alvo (ex.: {@code skillKeys}); ausente/não-array = lista vazia. */
    private static List<String> stringListOf(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            if (element.isTextual() && !element.asText().isBlank()) {
                values.add(element.asText());
            }
        }
        return List.copyOf(values);
    }

    /**
     * A corrente time→arquiteto do alvo (ADR-0037, D2/M9): {@code dominio}/{@code foco} apontam
     * o time; o arquiteto designado é o dono do que nasce. Cada elo vazio é um 409 ACIONÁVEL —
     * o dispatch roda na tx do decide, então a Approval segue PENDING intacta.
     */
    private Agent arquitetoDoTimeAlvo(Approval approval, JsonNode alvo) {
        String dominio = textOrNull(alvo, "dominio");
        String foco = textOrNull(alvo, "foco");
        if (dominio == null || foco == null) {
            throw new ConflictException("Proposta de issue sem time (dominio/foco) — nada que o"
                    + " CEO cria nasce sem dono (ADR-0037): " + approval.getId());
        }
        Team time = teams.findByCompanyIdAndDominioAndFoco(approval.getCompanyId(), dominio, foco)
                .orElseThrow(() -> new ConflictException("O time " + dominio + " / " + foco
                        + " nao existe nesta company — rejeite e proponha de novo com um time"
                        + " existente, ou crie o time antes: " + approval.getId()));
        UUID arquitetoId = time.getArquitetoAgentId();
        if (arquitetoId == null) {
            throw new ConflictException("O time " + dominio + " / " + foco + " esta sem arquiteto"
                    + " — designe um no board antes de aprovar: " + approval.getId());
        }
        return agents.findById(arquitetoId)
                .orElseThrow(() -> new ConflictException("O arquiteto do time " + dominio + " / "
                        + foco + " nao existe mais no quadro — designe outro antes de aprovar: "
                        + approval.getId()));
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
