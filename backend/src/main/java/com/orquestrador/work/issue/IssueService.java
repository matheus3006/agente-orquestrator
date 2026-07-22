package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.BadRequestException;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.runtime.ColetaDeWorkspace;
import com.orquestrador.governance.approval.RacionalPolicy;
import com.orquestrador.identity.company.CompanyRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionGrau;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.GateDecisionFactory;
import com.orquestrador.memory.GateDecisionFactory.Anchor;
import com.orquestrador.memory.GateDecisionFactory.GatePassage;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewDecision;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class IssueService {

    private final IssueRepository issues;
    private final CompanyRepository companies;
    private final ReviewRepository reviews;
    private final HeartbeatRunRepository heartbeatRuns;
    private final ActivityLogService activityLog;
    private final AgentRepository agents;
    private final TeamRepository teams;
    private final RacionalPolicy racionalPolicy;
    private final GateDecisionFactory gateDecisions;
    private final DecisionLog decisionLog;
    private final DecisionRepository decisions;
    private final ColetaDeWorkspace coletaDeWorkspace;
    private final int reviewRiskThreshold;

    public IssueService(IssueRepository issues, CompanyRepository companies, ReviewRepository reviews,
                        HeartbeatRunRepository heartbeatRuns, ActivityLogService activityLog,
                        AgentRepository agents, TeamRepository teams, RacionalPolicy racionalPolicy,
                        GateDecisionFactory gateDecisions, DecisionLog decisionLog,
                        DecisionRepository decisions, ColetaDeWorkspace coletaDeWorkspace,
                        @Value("${orquestrador.review.risk-threshold:5}") int reviewRiskThreshold) {
        // Fora da escala 1-5 o gate desligaria SILENCIOSAMENTE (limiar 6 nunca é atingível) —
        // fail-open de mecanismo de segurança. Melhor derrubar o boot com mensagem clara.
        if (reviewRiskThreshold < 1 || reviewRiskThreshold > 5) {
            throw new IllegalArgumentException(
                    "orquestrador.review.risk-threshold deve estar na escala 1-5 (ADR-0012); veio: "
                            + reviewRiskThreshold);
        }
        this.issues = issues;
        this.companies = companies;
        this.reviews = reviews;
        this.heartbeatRuns = heartbeatRuns;
        this.activityLog = activityLog;
        this.agents = agents;
        this.teams = teams;
        this.racionalPolicy = racionalPolicy;
        this.gateDecisions = gateDecisions;
        this.decisionLog = decisionLog;
        this.decisions = decisions;
        this.coletaDeWorkspace = coletaDeWorkspace;
        this.reviewRiskThreshold = reviewRiskThreshold;
    }

    @Transactional
    public Issue create(UUID companyId, String title, String description) {
        return create(companyId, title, description, null);
    }

    /**
     * Chokepoint unico de criacao (toda Issue nasce aqui — POST do board e CREATE_ISSUE do
     * approve). O #N (ADR-0033) e alocado sob o lock da linha da company: criacao de Issue nao
     * tem serializador natural (dono na UI + approve do CEO correm em threads concorrentes) —
     * o lock E o serializador do MAX+1; a unique (company_id, seq) e backstop, nao mecanismo.
     * Alocacao na MESMA tx do insert: rollback nao queima numero (o denso do contrato).
     */
    @Transactional
    public Issue create(UUID companyId, String title, String description, Integer riskLevel) {
        int effectiveRiskLevel = riskLevel != null ? riskLevel : 1;
        companies.findByIdForUpdate(companyId)
                .orElseThrow(() -> new NotFoundException("Company nao encontrada: " + companyId));
        int seq = issues.maxSeqByCompany(companyId) + 1;
        return issues.save(new Issue(UUID.randomUUID(), companyId, seq, title, description, effectiveRiskLevel));
    }

    /**
     * A task CORRETIVA do caso B (ADR-0034): nasce pelo MESMO chokepoint (lock da company +
     * MAX+1 — a ADR-0033 segue intocada), ligada a task que corrige ({@code corrige_issue_id})
     * e com o assignee PRE-SETADO (o arquiteto do time — a task nasce na mesa dele). Quem monta
     * titulo/descricao (verbatim do dono) e resolve o arquiteto e o caller (o roteador do caso B);
     * aqui e so o nascimento.
     */
    @Transactional
    public Issue createCorretiva(UUID companyId, String title, String description,
                                 UUID corrigeIssueId, UUID assigneeAgentId) {
        companies.findByIdForUpdate(companyId)
                .orElseThrow(() -> new NotFoundException("Company nao encontrada: " + companyId));
        int seq = issues.maxSeqByCompany(companyId) + 1;
        Issue corretiva = new Issue(UUID.randomUUID(), companyId, seq, title, description, 1);
        corretiva.nascerCorrigindo(corrigeIssueId, assigneeAgentId);
        return issues.save(corretiva);
    }

    /**
     * A issue da 2ª porta (ADR-0037, D2/M9): o CREATE_ISSUE aprovado no gate nasce pelo MESMO
     * chokepoint (lock da company + MAX+1) e JÁ COM DONO — o assignee pré-setado é o arquiteto
     * do time-alvo, resolvido pelo caller ({@code ApprovalService}). Espelho do
     * {@link #createCorretiva}, sem a ligação de correção.
     */
    @Transactional
    public Issue createAtribuida(UUID companyId, String title, String description,
                                 Integer riskLevel, UUID assigneeAgentId) {
        companies.findByIdForUpdate(companyId)
                .orElseThrow(() -> new NotFoundException("Company nao encontrada: " + companyId));
        int seq = issues.maxSeqByCompany(companyId) + 1;
        Issue issue = new Issue(UUID.randomUUID(), companyId, seq, title, description,
                riskLevel != null ? riskLevel : 1);
        issue.nascerAtribuida(assigneeAgentId);
        return issues.save(issue);
    }

    /**
     * A issue DIRIGIDA SEM GATE (ADR-0047, D-DSG-5/6): o pouso é o MESMO {@link #createAtribuida}
     * dos demais caminhos (chokepoint + assignee pré-setado = o agente específico que a gestora
     * escolheu, não o arquiteto), com UM aditivo — o selo {@code dirigida_por_gestor_agent_id}.
     * Só o caminho nasce-decidida (gestor PROMOVIDO) chama isto; o ramo ESTÁGIO aprovado em lote
     * pousa pelo {@code createAtribuida} puro e NÃO ganha selo (decisão consciente do dono).
     */
    @Transactional
    public Issue createDirigida(UUID companyId, String title, String description, Integer riskLevel,
                                UUID assigneeAgentId, UUID dirigidaPorGestorAgentId) {
        Issue issue = createAtribuida(companyId, title, description, riskLevel, assigneeAgentId);
        issue.selarDirigidaPor(dirigidaPorGestorAgentId);
        return issues.save(issue);
    }

    /**
     * Checkout atômico: o Run detém a Issue carimbando {@code execution_lock_run_id} com o próprio
     * {@code runId}, e o banco arbitra a corrida ({@link IssueRepository#tryCheckout}).
     *
     * <p><b>O {@code runId} é EXIGIDO e tem de ter PROCEDÊNCIA</b> (Fatia I-ter, A1). Até aqui este
     * método fazia {@code effectiveRun = runId != null ? runId : UUID.randomUUID()} — e essa linha
     * era a FONTE do pior lock órfão da casa. O lock nascido dela nomeia um Run que <b>não
     * existe</b>, e o {@code POST /checkout} devolvia esse runId ao cliente como se valesse: em ≤5 s
     * o {@code LockOrfaoReaper} lia corretamente "nenhum Run com futuro segura este lock" e
     * liquidava em {@code BLOCKED} <b>a Issue viva</b> que o cliente acabara de pegar. O
     * {@code /complete} seguinte dava 409 e só {@code unblock} humano devolvia.
     *
     * <p><b>Por que a cerca mora AQUI e não no controller.</b> É o chokepoint: o balcão HTTP e o
     * {@code AdapterRunExecutor} entram pela mesma porta, e um controller novo amanhã não pode
     * reabrir o furo por esquecimento. É também o precedente da casa — a checagem "toda review
     * corresponde a um run REAL desta company" já vive no {@link #submitForReview}, não no
     * {@code IssueController}.
     *
     * <p><b>Isto NÃO estreita o motor:</b> o executor chama com os quatro campos do mesmo Run que
     * está executando, então a cerca é verdadeira lá por construção — ver
     * {@link com.orquestrador.execution.HeartbeatRunRepository#existsByIdAndCompanyIdAndAgentIdAndIssueId}.
     *
     * <p><b>O que a cerca fecha e a REDE não podia:</b> o 6º produtor, intra-company — Issue Y
     * carimbada com o runId de um Run VIVO da Issue Z da mesma company. Aquele lock nomeia um Run
     * <i>com futuro</i>, então a varredura, por construção, <b>nunca</b> o alcança. Só a fonte cura.
     */
    @Transactional
    public CheckoutResult checkout(UUID companyId, UUID issueId, UUID agentId, UUID runId) {
        exigirProcedenciaDoRun(companyId, issueId, agentId, runId);
        int rows = issues.tryCheckout(issueId, companyId, agentId, runId);
        if (rows == 0) {
            return CheckoutResult.conflict();
        }
        Issue issue = issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada: " + issueId));
        return CheckoutResult.acquired(issue, runId);
    }

    /**
     * <b>"Este agente pode agir em nome deste run, sobre esta Issue?"</b> — a cerca de PROCEDÊNCIA
     * (Fatia I-ter), exigida por {@link #checkout} e {@link #complete}, os dois caminhos
     * agent-facing que escrevem {@code execution_lock_run_id}.
     *
     * <p>Um único 403 para todos os motivos, <b>de propósito</b>: distinguir "esse run não existe"
     * de "esse run não é seu" transformaria o endpoint num oráculo de existência de runs para
     * qualquer chave de agente da company. A mensagem também não repete o {@code runId} recebido.
     *
     * <p>Note que a cerca é sobre <b>identidade</b>, não sobre <b>liveness</b>: um Run em status
     * terminal tem procedência plena. É deliberado — o destrave manual da
     * {@code docs/ficha-seguranca-complete.md} conclui uma Issue travada com o runId de um Run que
     * já morreu, e ele continua funcionando para quem é dono do run.
     */
    private void exigirProcedenciaDoRun(UUID companyId, UUID issueId, UUID agentId, UUID runId) {
        if (runId == null || !heartbeatRuns.existsByIdAndCompanyIdAndAgentIdAndIssueId(
                runId, companyId, agentId, issueId)) {
            throw new ForbiddenException(
                    "runId sem procedencia: um agente so age em nome de um run proprio desta issue");
        }
    }

    /**
     * <b>"Este run AINDA detém esta Issue?"</b> — o mesmo predicado de posse que o {@code where} de
     * {@link IssueRepository#tryBlock}/{@link IssueRepository#tryComplete} usa para arbitrar a
     * corrida, exposto como PERGUNTA para quem precisa decidir <b>antes</b> de agir em nome do run.
     *
     * <p>Existe porque o guard do settle protege só o settle. Quem dispara side-effect antes dele —
     * os 4 gates de fim-de-run — agia incondicionalmente, e uma "thread tardia" (nó que perdeu o
     * lease, foi liquidado por outro e só então acordou) criava Approval/issue/parecer em nome de um
     * run que já não detinha nada. Reusar o predicado, e não inventar um invariante novo, é o que
     * mantém as duas cercas impossíveis de divergir.
     *
     * <p>Fail-CLOSED por construção: Issue inexistente (apagada, company removida) ⇒ {@code false}.
     * Sem Issue não há posse a invocar.
     */
    @Transactional(readOnly = true)
    public boolean runAindaDetemAIssue(UUID companyId, UUID issueId, UUID runId) {
        return issues.findByIdAndCompanyId(issueId, companyId)
                .map(issue -> runId != null && runId.equals(issue.getExecutionLockRunId()))
                .orElse(false);
    }

    /**
     * Conclusão pelo caminho agent-facing (endpoint /complete): roteia pelo MESMO funil do
     * {@link #settleAfterRun} — alto risco estaciona em PENDING_REVIEW em vez de DONE. Um único
     * caminho para DONE fecha o bypass "o agente pulou a revisão" (ADR-0006/0012; achado ALTA
     * da revisão adversarial da R.2).
     *
     * <p><b>Recebe o {@code agentId} do ator porque a identidade dele é parte do guard</b>
     * (Fatia I-ter, A2). O {@code IssueController.complete} autenticava com {@code requireAgent(...)}
     * e <b>descartava o retorno</b> — o único guard restante era o lock, que a listagem de issues
     * publica. Qualquer chave de agente da company liquidava qualquer Issue em nome de qualquer run.
     * O parâmetro existe para tornar o descarte impossível: não há como chamar este método sem
     * dizer <b>quem</b> está concluindo.
     *
     * <p>A cerca de procedência roda ANTES do funil, e o guard do lock ({@code tryComplete}) segue
     * valendo depois: um é sobre <b>quem</b> pode agir em nome do run, o outro sobre <b>se o run
     * ainda detém</b> a Issue. Nenhum substitui o outro.
     */
    @Transactional
    public boolean complete(UUID companyId, UUID issueId, UUID runId, UUID agentId) {
        exigirProcedenciaDoRun(companyId, issueId, agentId, runId);
        return settleAfterRun(companyId, issueId, runId, RunOutcome.success()) != IssueSettlement.NOT_SETTLED;
    }

    /**
     * Liquida a Issue na saída terminal de um Run (recovery ADR-0011 + review gate ADR-0012).
     * Sucesso → {@code DONE}, OU {@code PENDING_REVIEW} se {@code risk_level >= limiar} (o gate intercepta
     * o DONE: estaciona + cria o ciclo de {@code review} PENDING + evento, na MESMA tx); falha →
     * {@code BLOCKED} (park-for-human) + evento {@code ISSUE_BLOCKED}. SEMPRE libera o lock, sob o
     * guard {@code execution_lock_run_id = runId}: se o run NÃO detinha a Issue (falhou antes do checkout,
     * ou perdeu a corrida), casa 0 linhas → {@link IssueSettlement#NOT_SETTLED} (a vítima fica intacta) —
     * inclusive no branch de review (nenhum ciclo/evento é criado por um run intruso).
     *
     * <p>Recebe o {@link RunOutcome} (não um estado-alvo) para o futuro: quando o erro tipado do ADR-0004
     * for cabeado, o ramo {@code transient_upstream → RECOVERING}(+retryNotBefore) entra AQUI, sem tocar o caller.
     */
    @Transactional
    public IssueSettlement settleAfterRun(UUID companyId, UUID issueId, UUID runId, RunOutcome outcome) {
        return settleAfterRun(companyId, issueId, runId, outcome, ActorType.AGENT);
    }

    /**
     * O mesmo settle, com o ATOR explícito (Fatia I-bis, item 6). Existe porque nem toda liquidação
     * é ato de agente: a varredura do {@code LockOrfaoReaper} é de <b>sistema</b>, e gravar
     * {@code AGENT} + {@code assignee} nela <b>imputa ao agente um ato que não foi dele</b> — logo
     * onde alguém vai olhar para entender por que a Issue saiu de {@code IN_PROGRESS}. O precedente
     * da casa é o {@code WorkspaceReaper.recordCollected} ({@code SYSTEM}, sem {@code actorId}).
     *
     * <p>O ator só alcança o ramo {@code BLOCKED} porque é o único que uma varredura produz: ela
     * liquida com {@code RunOutcome.failure}, nunca com sucesso. Os ramos de sucesso/review seguem
     * sendo de agente por construção — quem entrega é sempre um run de agente.
     */
    @Transactional
    public IssueSettlement settleAfterRun(UUID companyId, UUID issueId, UUID runId, RunOutcome outcome,
                                          ActorType ator) {
        // R22-A (ADR-0011 emendada): run NO-OP nunca fecha issue — nem DONE nem review (não há
        // entregável a revisar). O guard mora AQUI, no chokepoint que cunha o DONE: mesmo um
        // outcome "verde" com noop cai no park-for-human abaixo (o board não volta a mentir).
        if (outcome.succeeded() && !outcome.noop()) {
            Issue settling = issues.findByIdAndCompanyId(issueId, companyId).orElse(null);
            if (settling != null && settling.getRiskLevel() >= reviewRiskThreshold) {
                return submitForReview(companyId, issueId, runId, settling.getAssigneeAgentId());
            }
            return issues.tryComplete(issueId, companyId, runId) == 1
                    ? IssueSettlement.COMPLETED : IssueSettlement.NOT_SETTLED;
        }
        if (issues.tryBlock(issueId, companyId, runId) == 0) {
            return IssueSettlement.NOT_SETTLED;
        }
        // Ator = o agente que rodou (assignee fixado no checkout) — ou SYSTEM, e aí NÃO há ator
        // individual: uma varredura não é ato de ninguém em particular. detail só com IDs (ADR-0009):
        // sem a mensagem de erro (pode conter texto sensível — o erro vive no StepTrace do run).
        UUID actingAgentId = ator == ActorType.SYSTEM ? null
                : issues.findByIdAndCompanyId(issueId, companyId)
                        .map(Issue::getAssigneeAgentId).orElse(null);
        activityLog.record(companyId, ator, actingAgentId, ActivityAction.ISSUE_BLOCKED,
                "issue", issueId, Map.of("runId", runId));
        return IssueSettlement.BLOCKED;
    }

    /**
     * O review gate (ADR-0012): estaciona em PENDING_REVIEW (guard = só o run detentor; 0 linhas → no-op,
     * SEM criar ciclo nem evento) e abre o ciclo de review PENDING apontando o run revisado — tudo na
     * MESMA tx do settle. Ator do evento = o agente que rodou (assignee fixado no checkout); detail só
     * com IDs (ADR-0009) — o decision_reason futuro vive na tabela review, nunca no log.
     */
    private IssueSettlement submitForReview(UUID companyId, UUID issueId, UUID runId, UUID actingAgentId) {
        if (issues.trySubmitForReview(issueId, companyId, runId) == 0) {
            return IssueSettlement.NOT_SETTLED;
        }
        // A review referencia um run concluído REAL desta company ("toda review corresponde a um run",
        // ADR-0012; a FK da V11 é o backstop Postgres). O caminho manual da fatia 1 (runId sintético,
        // sem linha em heartbeat_run) e um runId de outra company são recusados: 409 + rollback TOTAL
        // (o UPDATE guardado acima desfaz — a Issue segue IN_PROGRESS com o lock).
        if (!heartbeatRuns.existsByIdAndCompanyId(runId, companyId)) {
            throw new ConflictException(
                    "review exige um run real do motor de heartbeat desta company; runId sintetico/alheio: " + runId);
        }
        Review cycle = reviews.save(new Review(UUID.randomUUID(), companyId, issueId, runId));
        activityLog.record(companyId, ActorType.AGENT, actingAgentId, ActivityAction.ISSUE_SUBMITTED_FOR_REVIEW,
                "issue", issueId, Map.of("reviewId", cycle.getId(), "runId", runId));
        return IssueSettlement.SUBMITTED_FOR_REVIEW;
    }

    /**
     * A decisão do board sobre uma Issue estacionada (review gate, ADR-0012): APPROVE →
     * {@code DONE}; REJECT → {@code BLOCKED} (o recovery é 100% o {@link #unblock} do §7 — o
     * feedback do revisor É a Decisão do reject, ENTREGUE ao executor no re-run pela dieta
     * materializada das Decisões da issue, ADR-0011 emendada na R19-A; NÃO há edição de Issue).
     * Transição guardada por
     * {@code status = 'PENDING_REVIEW'} (0 linhas → 409) + o ciclo {@code review} vira
     * APPROVED/REJECTED com reviewer/decided_at na MESMA tx. O {@code decision_reason} (texto
     * livre) fica NA TABELA review; o evento carrega só o {@code reviewId} (regra PII, ADR-0009).
     *
     * <p>{@code expectedReviewId} (opcional) ANCORA a decisão no ciclo que o revisor viu: se o ciclo
     * aberto for outro (um APPROVE atrasado/duplicado chegando depois de reject→REOPEN→re-run abrir
     * um NOVO ciclo), 409 + rollback — uma decisão do ciclo N nunca aprova o output não-revisado do
     * ciclo N+1. Sem âncora, decide o ciclo corrente (conveniência do board solo v1).
     */
    @Transactional
    public void review(UUID companyId, UUID issueId, ReviewDecision decision, String reason,
                       UUID expectedReviewId, ActorType actorType, UUID actorId) {
        review(companyId, issueId, decision, reason, expectedReviewId, actorType, actorId, null);
    }

    /**
     * A review é FÁBRICA DE DECISÕES (F-C1 — writer D5 aceso, ADR-0019 emendada): o commit do
     * dono nesta porta também fabrica o átomo de memória, pela fábrica única (C15). O regime é
     * o C14 ("perguntar sempre, bloquear nunca"): REJECT exige motivo (bloqueia sem ele — o
     * motivo É a Decisão); APPROVE com racional fabrica; APPROVE em silêncio commita e fica SÓ
     * o {@code REVIEW_APPROVED} no activity_log — memória durável não nasce do nada (a regra
     * "sem substância → sem Decisão" mora na fábrica). A pergunta não-bloqueante é da UI
     * (momento) com as palavras da política ({@code RacionalPolicy.reviewProbe}).
     *
     * <p>{@code supersedesSeq} (opcional) é a linhagem D11 nesta porta: o dono sela o {@code #N}
     * citado; endereço fantasma → 409 acionável antes de qualquer efeito.
     */
    @Transactional
    public void review(UUID companyId, UUID issueId, ReviewDecision decision, String reason,
                       UUID expectedReviewId, ActorType actorType, UUID actorId, Integer supersedesSeq) {
        if (decision == ReviewDecision.REJECT && (reason == null || reason.isBlank())) {
            throw new BadRequestException("REJECT exige o motivo — ele bloqueia a entrega e vira a"
                    + " Decisao do registro (C14/ADR-0020): diga o que barrou");
        }
        UUID supersedesId = supersedesSeq != null
                ? decisionLog.idDoEndereco(companyId, supersedesSeq) : null;
        int rows = switch (decision) {
            case APPROVE -> issues.tryApprove(issueId, companyId);
            case REJECT -> issues.tryReject(issueId, companyId);
        };
        if (rows == 0) {
            throw new ConflictException("Issue nao esta PENDING_REVIEW (decisao so vale em revisao): " + issueId);
        }
        Review cycle = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issueId, ReviewStatus.PENDING)
                .orElseThrow(() -> new ConflictException(
                        "Issue em PENDING_REVIEW sem ciclo de review aberto (inconsistencia): " + issueId));
        if (expectedReviewId != null && !cycle.getId().equals(expectedReviewId)) {
            throw new ConflictException("decisao ancorada no ciclo " + expectedReviewId
                    + " mas o ciclo aberto e " + cycle.getId() + " (re-revise o output atual)");
        }
        if (decision == ReviewDecision.APPROVE) {
            cycle.approve(actorType, actorId, reason);
        } else {
            cycle.reject(actorType, actorId, reason);
        }
        reviews.save(cycle);
        ActivityAction action = decision == ReviewDecision.APPROVE
                ? ActivityAction.REVIEW_APPROVED : ActivityAction.REVIEW_REJECTED;
        activityLog.record(companyId, actorType, actorId, action, "issue", issueId,
                Map.of("reviewId", cycle.getId()));
        fabricaDecisaoDaReview(get(companyId, issueId), cycle, decision, reason, supersedesId);
    }

    /**
     * A porta pós-hoc como adapter FINO da fábrica única (C15): monta a passagem e entrega —
     * a área é a do ARTEFATO (C11) e o tipo do racional é DERIVADO dela (D9): artefato criativo
     * → INTUICAO → a maiêutica (D10) acompanha a Decisão como evidência.
     */
    private void fabricaDecisaoDaReview(Issue issue, Review cycle, ReviewDecision decision,
                                        String reason, UUID supersedesId) {
        String area = areaDaIssue(issue);
        String oQueMudou = "[" + decision.name() + " REVIEW] #" + issue.getSeq() + " " + issue.getTitle();
        gateDecisions.fabricate(new GatePassage(issue.getCompanyId(), area,
                origemDe(cycle), oQueMudou, reason, racionalPolicy.deriveTipo(area),
                false, supersedesId, DecisionGrau.DECISAO, null,
                new Anchor.NaReview(cycle.getId(), issue.getId())));
    }

    /**
     * A proveniência HONESTA da Decisão fabricada (ADR-0019/D4): {@code DONO_NO_GATE} quando quem
     * carimbou foi o board; {@code AGENTE} quando foi um gestor PROMOVIDO decidindo o próprio braço
     * (F-ORG-C/ADR-0048). Deriva do carimbo que acabou de ser gravado no ciclo — nenhum chamador
     * declara a própria proveniência.
     *
     * <p>É o primeiro writer de {@code DecisionOrigem.AGENTE} <b>pela porta do gate</b>
     * ({@code GateDecisionFactory}, D5 da ADR-0019) — e essa é a alegação inteira. A F-ORG-C
     * afirmou aqui que o enum "existia desde a ADR-0019 esperando writer, nenhum writer no v1":
     * FALSO, e corrigido no hardening do PR #50. Já havia DOIS writers na base —
     * {@code AbsorcaoDeIssueRouter} (ADR-0037) e {@code CorrecaoDeEntregaRouter} (ADR-0034) —, a
     * frase citada não é da ADR-0019 (é o javadoc stale de {@code memory/DecisionOrigem}) e a
     * reserva já tinha sido reivindicada por escrito em {@code docs/handoff-grilling-caso-b.md}.
     * O que segue verdadeiro: registrar decisão de gestor como DONO_NO_GATE seria rastro FALSO
     * justamente no caminho que mais precisa de rastro.
     */
    private static DecisionOrigem origemDe(Review cycle) {
        return cycle.getReviewerActorType() == ActorType.AGENT
                ? DecisionOrigem.AGENTE : DecisionOrigem.DONO_NO_GATE;
    }

    /**
     * A ÁREA do artefato da Issue (C11): a proveniência é o TIME do assignee — o {@code dominio}
     * do time é a área do trabalho que ele entrega (time de design → área criativa → INTUICAO →
     * D10 vivo). NUNCA um default fixo para todo review (senão tudo vira LOGICO e a maiêutica
     * morre de novo); "operacao" é só o fallback do caso sem assignee/time.
     */
    @Transactional(readOnly = true)
    public String areaDaIssue(Issue issue) {
        if (issue.getAssigneeAgentId() == null) {
            return "operacao";
        }
        return agents.findById(issue.getAssigneeAgentId())
                .map(Agent::getTeamId)
                .filter(Objects::nonNull)
                .flatMap(teamId -> teams.findByIdAndCompanyId(teamId, issue.getCompanyId()))
                .map(Team::getDominio)
                .orElse("operacao");
    }

    /**
     * Destrava uma Issue {@code BLOCKED} por decisão do board (ADR-0011): {@code REOPEN} → {@code OPEN}
     * (mantém o assignee → o próximo wakeup re-executa com o mesmo agente); {@code CANCEL} → {@code CANCELLED}.
     * Guardado por {@code status = 'BLOCKED'}: destravar uma Issue não-bloqueada → 409.
     */
    @Transactional
    public void unblock(UUID companyId, UUID issueId, UnblockDecision decision, ActorType actorType, UUID actorId) {
        int rows = switch (decision) {
            case REOPEN -> issues.tryReopen(issueId, companyId);
            case CANCEL -> issues.tryCancel(issueId, companyId);
        };
        if (rows == 0) {
            throw new ConflictException("Issue nao esta BLOCKED (destrava so vale para Issue bloqueada): " + issueId);
        }
        ActivityAction action = decision == UnblockDecision.REOPEN
                ? ActivityAction.ISSUE_REOPENED : ActivityAction.ISSUE_CANCELLED;
        activityLog.record(companyId, actorType, actorId, action, "issue", issueId, Map.of());
    }

    /**
     * REABRE uma entrega já APROVADA (ADR-0049 — decisão do dono, 2026-07-20). Fecha a assimetria
     * que a conferência do PR #50 achou: REJECT → BLOCKED é reabrível, APPROVE → DONE não era, e
     * {@code DONE} nunca aparecia como estado-ORIGEM em UPDATE guardado nenhum. Isso contradizia a
     * <b>D-ORG-2</b> — "gestor decide sozinho, com rastro total e <i>qualquer entrega reabrível
     * pelo dono</i>" —, e a reabertura é justamente a VÁLVULA que torna barato delegar decisão a um
     * agente: sem ela, promover é porta de mão única.
     *
     * <p><b>Board-only, revalidado AQUI e não só no controller</b> (lição do hardening item 2 do
     * PR #49: guard só na porta é seguro por CONVENÇÃO, não por construção). Um agente que
     * alcançasse este método reabriria a própria entrega reprovada — o inverso exato da cerca de
     * auto-review.
     *
     * <p><b>O motivo é obrigatório</b>, mesma régua do REJECT ({@code o motivo É a Decisão}): o
     * porquê de uma reabertura é a contra-evidência que informa se o gestor deve CONTINUAR
     * promovido. A D-ORG-2 promove quando "os pareceres batem"; este é o sinal do caminho inverso.
     *
     * <p><b>O que NÃO acontece</b>, de propósito: o ciclo de review continua APPROVED, com o
     * carimbo do gestor intacto. Voltá-lo a PENDING apagaria o rastro de que ELE decidiu — e o
     * rastro é a metade da D-ORG-2 que paga pela outra. A reabertura é fato SOMADO por cima, nunca
     * apagamento. Um ciclo NOVO nasce sozinho depois, se o dono der REOPEN e o re-run entregar de
     * novo em risco ≥ 5: aí o PENDING que o dono nunca viu existe de verdade, com entrega fresca
     * atrás dele.
     */
    @Transactional
    public void reabrirEntregaAprovada(UUID companyId, UUID issueId, String motivo,
                                       ActorType actorType, UUID actorId) {
        if (actorType != ActorType.BOARD) {
            throw new ForbiddenException("apenas o operador (board) reabre uma entrega aprovada"
                    + " (ADR-0049): reabertura é a válvula do DONO sobre a decisão delegada");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new BadRequestException("reabrir exige o motivo — ele desfaz uma entrega aceita e"
                    + " vira a Decisao do registro (ADR-0049): diga o que passou batido");
        }
        Issue issue = get(companyId, issueId);
        int rows = issues.tryReabrirAprovada(issueId, companyId);
        if (rows == 0) {
            throw new ConflictException("Issue nao esta DONE (reabertura so vale para entrega"
                    + " aprovada): " + issueId);
        }
        // FALHAR ALTO se o artefato já não existe (D-FP-1, decisão do dono de 2026-07-20). A
        // ADR-0049 tornou DONE reabrível e não varreu quem vivia do invariante "DONE é terminal":
        // o WorkspaceReaper coleta terminal vencida com retenção default de 7 DIAS e, ao
        // contrário do RowRetentionReaper, SEM exceção de pin de review. Sem esta cerca, reabrir
        // uma entrega mais velha que isso devolvia a issue ao jogo com o workspace já deletado e
        // o assignee re-executava contra o vazio, em SILÊNCIO. Recusa explícita > perda silenciosa:
        // o dono decide sabendo do custo em vez de descobrir depois que o agente refez do zero.
        //
        // Depois do UPDATE guardado de propósito: assim "não está DONE" continua sendo a resposta
        // de quem tenta reabrir o que nunca foi entregue, e esta mensagem fica reservada para o
        // caso real. O rollback do @Transactional desfaz o DONE→BLOCKED — a recusa é total.
        coletaDeWorkspace.coletadoEm(companyId, issueId).ifPresent(quando -> {
            throw new ConflictException("workspace da entrega foi coletado pelo GC em "
                    + ColetaDeWorkspace.formatado(quando) + " — reabrir nao recupera o artefato,"
                    + " o agente refaria do zero. Se e isso mesmo que voce quer, abra uma issue"
                    + " nova descrevendo o que falta: " + issueId);
        });
        activityLog.record(companyId, actorType, actorId, ActivityAction.ISSUE_REABERTA,
                "issue", issueId, Map.of());
        fabricaDecisaoDaReabertura(issue, motivo);
    }

    /**
     * A reabertura vira Decisão que SUPERSEDE a do carimbo desfeito (linhagem D11). Não basta o
     * activity_log: a Decisão é o que o executor LÊ na dieta do re-run (R19-A), e um
     * "[APPROVE REVIEW] #N" sem qualificação seguiria dizendo a todo agente futuro que a entrega
     * foi aceita. Origem {@code DONO_NO_GATE} — quem desfez foi o dono, mesmo que quem carimbou
     * tenha sido um gestor.
     *
     * <p>A antecessora é procurada pela âncora de CICLO; se não houver (APPROVE em silêncio não
     * fabrica Decisão — C14), a reabertura nasce sem {@code supersedes} em vez de não nascer.
     */
    private void fabricaDecisaoDaReabertura(Issue issue, String motivo) {
        UUID companyId = issue.getCompanyId();
        UUID cicloAprovado = reviews.findByCompanyIdAndIssueId(companyId, issue.getId()).stream()
                .filter(cycle -> cycle.getStatus() == ReviewStatus.APPROVED)
                .max(Comparator.comparing(Review::getDecidedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(Review::getId).orElse(null);
        UUID supersedesId = cicloAprovado == null ? null
                : decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(companyId, issue.getId()).stream()
                        .filter(decisao -> cicloAprovado.equals(decisao.getReviewId()))
                        .map(Decision::getId).reduce((primeira, ultima) -> ultima).orElse(null);
        String area = areaDaIssue(issue);
        gateDecisions.fabricate(new GatePassage(companyId, area, DecisionOrigem.DONO_NO_GATE,
                "[REABERTURA] #" + issue.getSeq() + " " + issue.getTitle(), motivo,
                racionalPolicy.deriveTipo(area), false, supersedesId, DecisionGrau.DECISAO, null,
                // Ramo morto removido (conferência dos PRs #51/#52, 🔵): o ternário tinha os dois
                // lados IDÊNTICOS — com `cicloAprovado` nulo, `new Anchor.NaReview(null, …)` é o
                // que a outra perna já produzia. A âncora aceita ciclo nulo de propósito (APPROVE
                // em silêncio não fabrica Decisão — C14), então não há caso especial a expressar.
                new Anchor.NaReview(cicloAprovado, issue.getId())));
    }

    @Transactional(readOnly = true)
    public List<Issue> list(UUID companyId) {
        return issues.findByCompanyId(companyId);
    }

    /**
     * O #N humano da task que esta corrige (caso B, ADR-0034/M7) — o selo "corrige #N" da
     * superfície. Null em task normal; null também se o alvo sumiu (defensivo — o selo se cala,
     * a coluna segue auditável).
     */
    @Transactional(readOnly = true)
    public Integer corrigeSeqDe(Issue issue) {
        if (issue.getCorrigeIssueId() == null) {
            return null;
        }
        return issues.findById(issue.getCorrigeIssueId()).map(Issue::getSeq).orElse(null);
    }

    @Transactional(readOnly = true)
    public Issue get(UUID companyId, UUID issueId) {
        return issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada: " + issueId));
    }

    /**
     * F-N2 (ADR-0038, D3): a conversa que ancora esta Issue (V27) — a chave do escopo de sessão do
     * executor. Null-safe (Issue sumida OU sem conversa ⟹ null): o executor cai em sessão fresca, o
     * baseline de hoje (a verdade-fonte do executor é o WORKSPACE). Ler o handle NUNCA aborta um run.
     */
    @Transactional(readOnly = true)
    public UUID conversationIdOf(UUID companyId, UUID issueId) {
        return issues.findByIdAndCompanyId(issueId, companyId)
                .map(Issue::getConversationId)
                .orElse(null);
    }
}
