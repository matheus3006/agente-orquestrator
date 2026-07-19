package com.orquestrador.governance.approval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.lineage.SpawnLineageGuard;
import com.orquestrador.memory.Decision;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

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
     * O DIRIGIR SEM GATE (ADR-0047, D-DSG-3/5): a rota do ramo PROMOVIDO — o ÚNICO call site do
     * caminho nasce-decidida ({@link #nasceDecididaPelaGestora}). Roda ATRÁS do guard de braço
     * (D-DSG-2, checado pelo {@code DirigirGate} no momento do ato) e é IDEMPOTENTE por
     * (run, ordem): settle reexecutado (reaper) devolve a linha existente em vez de duplicar
     * cartão+issue — a 1ª face do dedupe; o índice parcial da V37 é o backstop Postgres. Na MESMA
     * tx: Approval nasce-APPROVED → issue já atribuída ao alvo com o selo (D-DSG-6) →
     * {@code ISSUE_DIRECIONADA_SEM_GATE} com detail só-IDs (ADR-0009).
     */
    @Transactional
    public Approval dirigirIssueSemGate(UUID companyId, UUID gestoraAgentId, UUID runId,
                                        OrdemDirigida ordem) {
        String fingerprint = fingerprintOf(ordem.targetDetailJson());
        Approval jaProtocolada = approvals.findFirstByCompanyIdAndRunIdAndActionTypeAndTargetFingerprint(
                companyId, runId, ApprovalActionType.DIRECT_ISSUE, fingerprint).orElse(null);
        if (jaProtocolada != null) {
            return jaProtocolada;
        }
        Approval approval = nasceDecididaPelaGestora(companyId, gestoraAgentId, runId, ordem, fingerprint);
        Issue issue = issues.createDirigida(companyId, ordem.titulo(), ordem.descricao(),
                ordem.risco(), ordem.targetAgentId(), gestoraAgentId);
        activityLog.record(companyId, ActorType.AGENT, gestoraAgentId,
                ActivityAction.ISSUE_DIRECIONADA_SEM_GATE, "issue", issue.getId(),
                Map.of("approvalId", approval.getId(), "targetAgentId", ordem.targetAgentId(),
                        "targetTeamId", ordem.targetTeamId(), "bracoTeamId", ordem.bracoTeamId(),
                        "runId", runId));
        return approval;
    }

    /**
     * A PRIMEIRA Approval da casa que NASCE decidida (ADR-0047, emenda consciente a ADR-0018/0020
     * — decisão explícita do dono no grill dirigir-sem-gate, D-DSG-3): constrói e chama
     * {@code decide()} ANTES do primeiro save — nunca existe uma linha PENDING, nunca passa por
     * {@code propose()}, o grill do racional NÃO roda (o racional já vem OBRIGATÓRIO do bloco,
     * D-DSG-4; {@code decisionReason} fica null). {@code racional_tipo} é derivado pelo MESMO
     * {@code areaFor}/{@code deriveTipo} do propose — os dois ramos do DIRECT_ISSUE classificam
     * idêntico (área "backlog" → LOGICO).
     *
     * <p><b>Escopo de segurança (conferência da sessão de controle, NÃO-NEGOCIÁVEL):</b> este
     * método é PRIVADO, com {@code ApprovalActionType.DIRECT_ISSUE} e {@code ActorType.AGENT}
     * como LITERAIS FIXOS — nunca parâmetros — e tem UM único call site
     * ({@link #dirigirIssueSemGate}, atrás do guard de braço). Um helper genérico
     * "cria-e-já-decide" parametrizável seria um bypass PERMANENTE de
     * {@code propose()}+{@code decide()}+guard-de-board pra QUALQUER tipo de ação — proibido por
     * desenho; generalizar exige decisão nova com revisão de segurança própria.
     */
    private Approval nasceDecididaPelaGestora(UUID companyId, UUID gestoraAgentId, UUID runId,
                                              OrdemDirigida ordem, String fingerprint) {
        String area = racionalPolicy.areaFor(ApprovalActionType.DIRECT_ISSUE, null);
        Approval approval = new Approval(UUID.randomUUID(), companyId, gestoraAgentId, null, runId,
                ApprovalActionType.DIRECT_ISSUE, ordem.targetDetailJson(), fingerprint, area,
                ordem.resumo(), racionalPolicy.deriveTipo(area));
        // O cinto contra reuso acidental: se uma edição futura fizer o tipo virar parâmetro (ou
        // trocar o literal acima), o caminho nasce-decidida para de funcionar em vez de virar
        // porta genérica — o compilador não pega isso; este assert pega.
        if (approval.getActionType() != ApprovalActionType.DIRECT_ISSUE) {
            throw new IllegalStateException("nasce-decidida e EXCLUSIVA do DIRECT_ISSUE (ADR-0047)"
                    + " — outro tipo de acao passa por propose()+decide()");
        }
        approval.decide(ApprovalDecision.APPROVE, ActorType.AGENT, gestoraAgentId,
                ordem.racional().strip(), null);
        return approvals.save(approval);
    }

    /** Uma ordem do bloco ```dirigir``` já resolvida pelo guard: alvo por ID, braço confirmado. */
    public record OrdemDirigida(UUID targetAgentId, UUID targetTeamId, UUID bracoTeamId,
                                String titulo, String descricao, Integer risco, String racional,
                                String targetDetailJson, String resumo) {
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

    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride, Integer supersedesSeq) {
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId,
                engineOverride, supersedesSeq, null);
    }

    /**
     * O commit com a MÃO SOBERANA no motor (A4, adendo ADR-0031): no menu do hire as skills são
     * somente-leitura, mas o TRIO do motor é livremente editável pelo dono — o override vence o
     * motor protocolado no alvo, sem rejeitar nem devolver ao Planejador.
     *
     * <p>{@code supersedesSeq} (opcional) é a linhagem D11 na porta pré-hoc: o CEO propõe o link
     * citando o {@code #N}, o dono sela aqui — a Decisão fabricada aponta a supersedida e a carta
     * se auto-cura (o leitor já filtra). {@code #N} inexistente → 409 acionável, nada commitado.
     *
     * <p>{@code fraseDoPrincipio} (opcional, F-C2/C2) é a mão soberana do dono na 7ª ação: a frase
     * EDITADA no card VENCE a proposta do CEO — o dono é o writer-of-record do Playbook. Em branco
     * cai na frase protocolada no alvo; ignorada nas demais ações.
     */
    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride, Integer supersedesSeq,
                                String fraseDoPrincipio) {
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId,
                engineOverride, supersedesSeq, fraseDoPrincipio, true);
    }

    /**
     * O núcleo do commit. {@code fabricaDecisaoPorItem} (ADR-0035 × F-C1) governa só o REGISTRO da
     * Decisão: {@code true} no caminho individual (a porta fabrica a sua, C15); {@code false} no
     * lote via {@link #decideNoLote} — lá o dispatch da ação real roda igual, mas a Decisão migra
     * pro fim do lote ({@link #fabricaDecisaoDoLote} registra UMA por área). O skip de
     * GRADUATE_PRINCIPLE-APPROVE (o dispatch já fabricou o princípio) é INDEPENDENTE desse flag.
     */
    @Transactional
    public DecideOutcome decide(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                String racional, String reason, ActorType actorType, UUID actorId,
                                EngineOverride engineOverride, Integer supersedesSeq,
                                String fraseDoPrincipio, boolean fabricaDecisaoPorItem) {
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
            dispatchAction(approval, actorType, actorId, detail, engineOverride, supersedesId,
                    fraseDoPrincipio);
        }
        approvals.save(approval);

        ActivityAction action = decision == ApprovalDecision.APPROVE
                ? ActivityAction.APPROVAL_APPROVED : ActivityAction.APPROVAL_REJECTED;
        activityLog.record(companyId, actorType, actorId, action, "approval", approval.getId(), detail);

        // F-C2 (C6): no APPROVE de graduação o dispatch JÁ fabricou a Decisão da passagem — o
        // princípio É o registro do gate (uma passagem, UMA Decisão; uma irmã grau=DECISAO
        // duplicaria o endereço). No REJECT a porta registra a recusa como Decisão comum.
        // ADR-0035 × F-C1: no LOTE (fabricaDecisaoPorItem=false) o registro migra pro fim do lote
        // (fabricaDecisaoDoLote: UMA por área, nunca N clones) — o skip da graduação é ORTOGONAL.
        if (fabricaDecisaoPorItem
                && !(decision == ApprovalDecision.APPROVE
                    && approval.getActionType() == ApprovalActionType.GRADUATE_PRINCIPLE)) {
            fabricaDecisaoDoGate(approval, decision, supersedesId);
        }
        return new DecideOutcome.Committed(approval);
    }

    /**
     * O item do LOTE (ADR-0035 × F-C1): decide + dispara a ação real, mas NÃO fabrica a Decisão
     * por item — {@link #fabricaDecisaoDoLote} a registra UMA vez depois do loop. Cada item na
     * própria tx: o {@link ApprovalBatchService} é deliberadamente não-transacional (D9). Sem
     * override de motor nem supersede no lote (D11/D12) — o que viaja é decisão + racional + reason.
     */
    @Transactional
    public DecideOutcome decideNoLote(UUID companyId, UUID approvalId, ApprovalDecision decision,
                                      String racional, String reason, ActorType actorType, UUID actorId) {
        return decide(companyId, approvalId, decision, racional, reason, actorType, actorId,
                null, null, null, false);
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
                approval.isRacionalArrancado(), supersedesId, DecisionGrau.DECISAO, null,
                new Anchor.NaApproval(approval.getId())));
    }

    /**
     * 1 Decisão por (lote × área) — ADR-0035 × F-C1: um ato do dono com N alvos NÃO vira N Decisões
     * clonadas (que envenenariam o log append-only e acenderiam a nudge C5 falsa na carta). A
     * linhagem dos N vai na {@code evidencia}, como a graduação registra suas fontes (C5). Agrupa por
     * área porque o TIPO ({@link DecisionLog#tipoFor}) e o detector C5 são POR-ÁREA — no lote
     * homogêneo real (mesmo tipo → mesma área) é UMA Decisão. Pula GRADUATE_PRINCIPLE-APPROVE (o
     * dispatch já fabricou o princípio — simetria com o skip por-item do {@link #decide}). FORA de
     * tx: cada {@code fabricate} abre a sua (uma por área), respeitando o não-transacional do lote —
     * a Decisão reflete só as {@code decididas} (os itens que de fato commitaram).
     */
    public void fabricaDecisaoDoLote(ApprovalDecision decision, String racional, List<Approval> decididas) {
        Map<String, List<Approval>> porArea = new LinkedHashMap<>();
        for (Approval approval : decididas) {
            if (decision == ApprovalDecision.APPROVE
                    && approval.getActionType() == ApprovalActionType.GRADUATE_PRINCIPLE) {
                continue; // o dispatch já fabricou o princípio (uma passagem, uma Decisão)
            }
            porArea.computeIfAbsent(approval.getArea(), area -> new ArrayList<>()).add(approval);
        }
        porArea.forEach((area, doGrupo) -> fabricaUmaDoLote(decision, racional, area, doGrupo));
    }

    /** A Decisão única de um grupo do lote (uma área): a linhagem dos alvos vai na evidência (C5). */
    private void fabricaUmaDoLote(ApprovalDecision decision, String racional, String area,
                                  List<Approval> doGrupo) {
        Approval amostra = doGrupo.get(0);
        DecisionOrigem origem = decision == ApprovalDecision.APPROVE
                ? DecisionOrigem.PROPOSTA_CEO   // conteúdo do CEO, selado pelo dono
                : DecisionOrigem.DONO_NO_GATE;  // o dono barrou/redirecionou o quadro
        String alvos = doGrupo.stream().map(ApprovalService::resumoDe).collect(Collectors.joining("; "));
        String oQueMudou = "[" + decision.name() + " " + amostra.getActionType().name() + " ×"
                + doGrupo.size() + "] " + alvos;
        String evidencia = "Lote (ADR-0035): 1 ato do dono, " + doGrupo.size() + " alvos — " + alvos;
        gateDecisions.fabricate(new GatePassage(amostra.getCompanyId(), area, origem, oQueMudou,
                racional, amostra.getRacionalTipo(), amostra.isRacionalArrancado(), null,
                DecisionGrau.DECISAO, evidencia, new Anchor.NoLote()));
    }

    /** O resumo humano da approval — o rótulo do alvo na linhagem do lote (fallback: ação + alvo). */
    private static String resumoDe(Approval approval) {
        return approval.getResumo() != null && !approval.getResumo().isBlank()
                ? approval.getResumo()
                : approval.getActionType().name() + " " + approval.getTargetDetail();
    }

    /** APPROVE dispara a ação REAL com ator=aprovador (ADR-0018); os IDs criados vão no detail. */
    private void dispatchAction(Approval approval, ActorType actorType, UUID actorId,
                                Map<String, Object> detail, EngineOverride engineOverride,
                                UUID supersedesId, String fraseDoPrincipio) {
        JsonNode alvo = parseAlvo(approval);
        switch (approval.getActionType()) {
            case HIRE_AGENT -> {
                String name = textOrNull(alvo, "name");
                String role = textOrNull(alvo, "role");
                if (name == null || role == null) {
                    throw new ConflictException("Proposta de hire sem name/role: " + approval.getId());
                }
                // ADR-0045 (D-ORG-9): o hire agora carrega o TIME (por dominio/foco, como a issue —
                // quem escreve o bloco é um LLM, e UUID ele alucina). Sem time não há contratação:
                // proposta antiga (ou sem time) vira 409 acionável em vez de agente órfão.
                Team timeDoHire = timeDoAlvo(approval, alvo);
                avisarSeMandouChefe(approval, alvo);
                // Motor (A4): o alvo protocolado carrega o trio sugerido; o override do dono no
                // menu VENCE campo-a-campo (mão soberana — CEO tem viés de motor).
                EngineOverride doDono = engineOverride == null ? EngineOverride.none() : engineOverride;
                Agent hired = hires.hire(approval.getCompanyId(), new AgentHireService.HireCommand(
                                name, role, timeDoHire.getId(),
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
                // ADR-0045: o time nasce PENDURADO na árvore. `paiDominio`/`paiFoco` ausentes =
                // filho da RAIZ (um departamento) — que é exatamente o caso do "crie o
                // departamento de marketing" da D-ORG-9.
                Team pai = paiDoTimeAlvo(approval, alvo);
                Team time = teamService.create(approval.getCompanyId(), dominio, foco,
                        pai == null ? null : pai.getId());
                // O trio de motor do arquiteto respeita a mão soberana do dono no menu (A4).
                EngineOverride doDono = engineOverride == null ? EngineOverride.none() : engineOverride;
                // O arquiteto já nasce DENTRO do time recém-criado (hire exige time, ADR-0045) —
                // some o passo "contrata solto e depois move".
                Agent arquiteto = hires.hire(approval.getCompanyId(), new AgentHireService.HireCommand(
                                nomeArquiteto, roleArquiteto, time.getId(),
                                preferNonBlank(doDono.adapterType(), textOrNull(arquitetoAlvo, "adapterType")),
                                null, null,
                                preferNonBlank(doDono.model(), textOrNull(arquitetoAlvo, "model")),
                                preferNonBlank(doDono.effort(), textOrNull(arquitetoAlvo, "effort")),
                                uuidOrNull(arquitetoAlvo, "roleTemplateVersionId"),
                                approval.getRequestedByAgentId()),
                        actorType, actorId);
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
            case DIRECT_ISSUE -> {
                // ADR-0047 (D-DSG-8): o ramo ESTÁGIO aprovado — o dono viu COMO o gestor dirige
                // antes de confiar. O pouso é o MESMO createAtribuida do caminho sem-gate, mas o
                // alvo é o AGENTE ESPECÍFICO do bloco (a gestora já triou; resolvido por nome →
                // UUID no momento do ato pelo DirigirGate), NUNCA o arquiteto-do-time do
                // CREATE_ISSUE. SEM selo: passou pelo gate como qualquer outra (D-DSG-6). O guard
                // de braço NÃO é reconferido aqui: valeu no ATO (D-DSG-2); daqui pra frente quem
                // autoriza é o humano que está aprovando o cartão — gestor trocado/rebaixado no
                // meio-tempo é informação visível no cartão, não bloqueio.
                // ⚠️ Este switch é STATEMENT sem default: sem este case, aprovar um cartão
                // DIRECT_ISSUE compilaria e viraria no-op SILENCIOSO (Approval APPROVED, zero
                // issue) — o teste do case em DirigirGateTest é o cinto obrigatório.
                String titulo = textOrNull(alvo, "titulo");
                UUID targetAgentId = uuidOrNull(alvo, "targetAgentId");
                if (titulo == null || targetAgentId == null) {
                    throw new ConflictException("Direcionamento sem titulo/targetAgentId: " + approval.getId());
                }
                Agent dirigido = agents.findById(targetAgentId)
                        .orElseThrow(() -> new ConflictException("O agente dirigido nao existe mais"
                                + " no quadro — rejeite e peça reemissao: " + approval.getId()));
                Issue issue = issues.createAtribuida(approval.getCompanyId(), titulo,
                        textOrNull(alvo, "descricao"),
                        alvo.path("risco").isInt() ? alvo.path("risco").asInt() : null,
                        dirigido.getId());
                detail.put("issueId", issue.getId());
                detail.put("assigneeAgentId", dirigido.getId());
            }
            case GRADUATE_PRINCIPLE -> {
                // F-C2 (C2/C6): a graduação — o CEO propôs a FRASE, o dono sela (writer-of-record).
                // A "ação real" É a Decisão grau=PRINCIPIO pela fábrica única (approval_id NOT NULL
                // de graça); a linhagem C5 vai em evidência ("graduado de #N…" — documentação
                // resolvível, nunca FK) e o PLAYBOOK materializa na mesma tx.
                String frase = preferNonBlank(fraseDoPrincipio, textOrNull(alvo, "frase"));
                if (frase == null) {
                    throw new ConflictException(
                            "Proposta de graduacao sem a frase do principio: " + approval.getId());
                }
                Decision principio = gateDecisions.fabricate(new GatePassage(approval.getCompanyId(),
                        approval.getArea(), DecisionOrigem.PROPOSTA_CEO, frase.strip(),
                        approval.getRacional(), approval.getRacionalTipo(),
                        approval.isRacionalArrancado(), supersedesId, DecisionGrau.PRINCIPIO,
                        linhagemDe(alvo), new Anchor.NaApproval(approval.getId())))
                        .orElseThrow(() -> new IllegalStateException(
                                "graduacao sem racional passou pelo grill: " + approval.getId()));
                detail.put("decisionSeq", principio.getSeq());
            }
        }
    }

    /** A linhagem da graduação (C5): os {@code #N} citados no alvo viram evidência resolvível. */
    private static String linhagemDe(JsonNode alvo) {
        List<String> seqs = new ArrayList<>();
        for (JsonNode seq : alvo.path("graduadaDe")) {
            if (seq.isInt()) {
                seqs.add("#" + seq.asInt());
            }
        }
        return seqs.isEmpty() ? null
                : "Graduado de " + String.join(", ", seqs) + " (C5 — linhagem resolvivel pelos #N)";
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
    /**
     * Resolve o TIME do alvo por (dominio, foco) — a mesma gramática do CREATE_ISSUE (ADR-0037):
     * o bloco de proposta é escrito por um LLM, que sabe o NOME do time e alucinaria um UUID.
     */
    private Team timeDoAlvo(Approval approval, JsonNode alvo) {
        String dominio = textOrNull(alvo, "dominio");
        String foco = textOrNull(alvo, "foco");
        if (dominio == null || foco == null) {
            throw new ConflictException("Proposta de hire sem time (dominio/foco) — todo agente"
                    + " pertence a um time (ADR-0045): rejeite e proponha de novo com o time: "
                    + approval.getId());
        }
        return teams.findByCompanyIdAndDominioAndFoco(approval.getCompanyId(), dominio, foco)
                .orElseThrow(() -> new ConflictException("O time " + dominio + " / " + foco
                        + " nao existe nesta company — rejeite e proponha de novo com um time"
                        + " existente, ou crie o time antes: " + approval.getId()));
    }

    /**
     * O PAI do time proposto (ADR-0045), por (paiDominio, paiFoco). Ausente = raiz (departamento).
     * Nomeado e inexistente é 409 — pendurar no lugar errado é pior que recusar.
     */
    private Team paiDoTimeAlvo(Approval approval, JsonNode alvo) {
        String paiDominio = textOrNull(alvo, "paiDominio");
        String paiFoco = textOrNull(alvo, "paiFoco");
        if (paiDominio == null || paiFoco == null) {
            return null; // filho da raiz = a empresa
        }
        return teams.findByCompanyIdAndDominioAndFoco(approval.getCompanyId(), paiDominio, paiFoco)
                .orElseThrow(() -> new ConflictException("O time-pai " + paiDominio + " / " + paiFoco
                        + " nao existe nesta company — rejeite e proponha de novo: " + approval.getId()));
    }

    /**
     * A chefia DERIVA da árvore (ADR-0045): {@code reportsToId} no alvo virou ruído. Ignorar em
     * silêncio seria o pecado que a ADR-0036 nomeou — então o descarte é CONTADO (WARN).
     */
    private void avisarSeMandouChefe(Approval approval, JsonNode alvo) {
        if (uuidOrNull(alvo, "reportsToId") != null) {
            log.warn("proposta {} mandou reportsToId no alvo do hire — IGNORADO: a chefia deriva da"
                    + " arvore de times (ADR-0045)", approval.getId());
        }
    }

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
