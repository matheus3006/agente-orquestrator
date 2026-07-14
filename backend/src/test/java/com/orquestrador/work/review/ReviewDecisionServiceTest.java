package com.orquestrador.work.review;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.BadRequestException;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import com.orquestrador.work.issue.UnblockDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R.3 (ADR-0012): a decisão do board sobre uma Issue estacionada. APPROVE → DONE; REJECT → BLOCKED
 * (reusa 100% o unblock do §7). A linha review vira APPROVED/REJECTED com reviewer/decided_at na
 * MESMA tx; o decision_reason fica NA TABELA (nunca no activity_log — ADR-0009). Guard
 * status='PENDING_REVIEW': decidir Issue não-estacionada → 409.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewDecisionServiceTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private TeamRepository teams;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        return agents.save(agent).getId();
    }

    /** Estaciona uma Issue de risco 5 em PENDING_REVIEW pelo caminho real do gate (run real + settle). */
    private UUID parkForReview(UUID companyId, UUID agentId) {
        UUID issueId = issues.create(companyId, "alto risco", null, 5).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.settleAfterRun(companyId, issueId, runId, RunOutcome.success());
        return issueId;
    }

    private Review singleReview(UUID companyId, UUID issueId) {
        List<Review> cycle = reviews.findByCompanyIdAndIssueId(companyId, issueId);
        assertThat(cycle).hasSize(1);
        return cycle.get(0);
    }

    private List<ActivityLog> entriesOf(UUID companyId, ActivityAction action) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entry -> entry.getAction().equals(action.name())).toList();
    }

    @Test
    void approveLevaADoneEDecideAReview() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgent(companyId));
        UUID boardUser = UUID.randomUUID();

        issues.review(companyId, issueId, ReviewDecision.APPROVE, null, null, ActorType.BOARD, boardUser);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        Review decided = singleReview(companyId, issueId);
        assertThat(decided.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(decided.getReviewerActorType()).isEqualTo(ActorType.BOARD);
        assertThat(decided.getReviewerActorId()).isEqualTo(boardUser);
        assertThat(decided.getDecidedAt()).isNotNull();

        List<ActivityLog> events = entriesOf(companyId, ActivityAction.REVIEW_APPROVED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActorType()).isEqualTo(ActorType.BOARD.name());
        assertThat(events.get(0).getDetail()).contains(decided.getId().toString());
    }

    @Test
    void rejectLevaABlockedComReasonNaTabelaENuncaNoLog() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgent(companyId));
        String reason = "faltou validar o rollback do deploy";

        issues.review(companyId, issueId, ReviewDecision.REJECT, reason, null, ActorType.BOARD, UUID.randomUUID());

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        Review decided = singleReview(companyId, issueId);
        assertThat(decided.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(decided.getDecisionReason()).isEqualTo(reason);
        assertThat(decided.getDecidedAt()).isNotNull();

        List<ActivityLog> events = entriesOf(companyId, ActivityAction.REVIEW_REJECTED);
        assertThat(events).hasSize(1);
        // Regra PII (ADR-0009): o texto do motivo NUNCA aparece no evento.
        assertThat(events.get(0).getDetail()).doesNotContain("rollback");
    }

    /** Um agente DENTRO de um time (dominio = a área do artefato que ele entrega, C11). */
    private UUID newAgentInTeam(UUID companyId, String dominio) {
        Team time = teams.save(new Team(UUID.randomUUID(), companyId, dominio, "foco"));
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        agent.assignTeam(time.getId());
        return agents.save(agent).getId();
    }

    /**
     * F-C1 (C11 — writer D5 ACESO): a review é fábrica de Decisões. APPROVE com racional →
     * Decisão ancorada no ciclo ({@code review_id} deixa de ser sempre-NULL) com a ÁREA DO
     * ARTEFATO (o time do assignee) — nunca um default fixo, senão todo review vira LOGICO
     * e a maiêutica (D10) continua morta.
     */
    @Test
    void approveComRacionalFabricaDecisaoAncoradaNaReviewComAreaDaIssue() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgentInTeam(companyId, "growth"));

        issues.review(companyId, issueId, ReviewDecision.APPROVE,
                "entrega ok; risco aceito: sem teste de carga ainda", null,
                ActorType.BOARD, UUID.randomUUID());

        Review decided = singleReview(companyId, issueId);
        List<Decision> log = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId);
        assertThat(log).hasSize(1);
        Decision decisao = log.get(0);
        assertThat(decisao.getReviewId()).as("a coluna morta da V18 acorda").isEqualTo(decided.getId());
        assertThat(decisao.getIssueId()).isEqualTo(issueId);
        assertThat(decisao.getArea()).as("área do ARTEFATO = time do assignee (C11)").isEqualTo("growth");
        assertThat(decisao.getOrigem()).isEqualTo(DecisionOrigem.DONO_NO_GATE);
        assertThat(decisao.getRacional()).contains("risco aceito");
        assertThat(decisao.getOQueMudou()).contains("#1"); // aponta o artefato pelo endereço humano
    }

    /**
     * F-C1 (C14 — triviabilidade = silêncio-após-perguntado): approve EM SILÊNCIO commita e a
     * HISTÓRIA fica ({@code REVIEW_APPROVED} no activity_log), mas memória durável NÃO nasce do
     * nada — zero Decision. Não-gameável: quem não diz nada não ganha memória.
     */
    @Test
    void approveEmSilencioCommitaSemFabricarDecisao() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgentInTeam(companyId, "growth"));

        issues.review(companyId, issueId, ReviewDecision.APPROVE, null, null,
                ActorType.BOARD, UUID.randomUUID());

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(entriesOf(companyId, ActivityAction.REVIEW_APPROVED)).hasSize(1);
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId))
                .as("memória durável não nasce do nada").isEmpty();
    }

    /**
     * F-C1 (C13 — achado do grill): a review é onde o dono julga ARTEFATO, e artefato é onde
     * mora o gosto — time criativo → INTUICAO → a maiêutica (D10) dispara pela 1ª vez, como
     * evidência da Decisão (acompanha, nunca bloqueia).
     */
    @Test
    void reviewDeArtefatoCriativoDisparaAMaieutica() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgentInTeam(companyId, "design"));

        issues.review(companyId, issueId, ReviewDecision.APPROVE, "me agradou o tom leve da home",
                null, ActorType.BOARD, UUID.randomUUID());

        Decision decisao = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);
        assertThat(decisao.getRacionalTipo()).isEqualTo(RacionalTipo.INTUICAO); // derivada da área (D9)
        assertThat(decisao.getEvidencia()).as("D10 vivo pela 1ª vez").contains("maieutico");
    }

    /** F-C1 (C14): REJECT bloqueia SEM motivo — o motivo é a Decisão do registro; nada muda sem ele. */
    @Test
    void rejectSemMotivoEBloqueadoAntesDeQualquerEfeito() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgent(companyId));

        assertThatThrownBy(() -> issues.review(companyId, issueId, ReviewDecision.REJECT, "  ", null,
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("motivo");

        assertThat(issues.get(companyId, issueId).getStatus())
                .as("nenhum efeito: segue estacionada").isEqualTo(IssueStatus.PENDING_REVIEW);
        assertThat(singleReview(companyId, issueId).getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId)).isEmpty();
    }

    /** F-C1 (C11): REJECT com motivo fabrica a Decisão — problema apontado vira registro datado (D5). */
    @Test
    void rejectComMotivoFabricaDecisao() {
        UUID companyId = newCompany();
        UUID issueId = parkForReview(companyId, newAgentInTeam(companyId, "growth"));

        issues.review(companyId, issueId, ReviewDecision.REJECT, "faltou validar o rollback", null,
                ActorType.BOARD, UUID.randomUUID());

        List<Decision> log = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getRacional()).isEqualTo("faltou validar o rollback");
        assertThat(log.get(0).getReviewId()).isNotNull();
    }

    /**
     * F-C1 (C12 — writer D11 na porta pós-hoc): o dono sela o supersede citado também na review;
     * endereço fantasma → 409 acionável, decisão não commitada.
     */
    @Test
    void reviewSelaSupersedeCitadoEEnderecoFantasmaE409() {
        UUID companyId = newCompany();
        UUID agenteGrowth = newAgentInTeam(companyId, "growth");
        UUID primeira = parkForReview(companyId, agenteGrowth);
        issues.review(companyId, primeira, ReviewDecision.APPROVE, "risco aceito: sem cache", null,
                ActorType.BOARD, UUID.randomUUID());
        Decision alvo = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);

        UUID segunda = parkForReview(companyId, agenteGrowth);
        assertThatThrownBy(() -> issues.review(companyId, segunda, ReviewDecision.APPROVE,
                "agora com cache", null, ActorType.BOARD, UUID.randomUUID(), 99))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("#99");
        assertThat(issues.get(companyId, segunda).getStatus())
                .as("409 antes de qualquer efeito").isEqualTo(IssueStatus.PENDING_REVIEW);

        issues.review(companyId, segunda, ReviewDecision.APPROVE,
                "agora com cache — a ressalva anterior caiu", null,
                ActorType.BOARD, UUID.randomUUID(), alvo.getSeq());
        Decision nova = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> decision.getSupersedesId() != null).findFirst().orElseThrow();
        assertThat(nova.getSupersedesId()).isEqualTo(alvo.getId());
    }

    @Test
    void decidirIssueNaoEstacionadaFalhaCom409() {
        UUID companyId = newCompany();
        UUID issueId = issues.create(companyId, "aberta", null, 5).getId(); // OPEN, nunca estacionada
        assertThatThrownBy(() -> issues.review(companyId, issueId, ReviewDecision.APPROVE, null, null,
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);

        // Decidir duas vezes: a segunda encontra a Issue já DONE → 409 (guard status='PENDING_REVIEW').
        UUID parked = parkForReview(companyId, newAgent(companyId));
        issues.review(companyId, parked, ReviewDecision.APPROVE, null, null, ActorType.BOARD, UUID.randomUUID());
        assertThatThrownBy(() -> issues.review(companyId, parked, ReviewDecision.REJECT, "tarde demais", null,
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void approveStaleAncoradoNoCicloAnteriorNaoAprovaOCicloNovo() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = parkForReview(companyId, agentId);
        UUID primeiroCiclo = singleReview(companyId, issueId).getId();

        // Ciclo 1 rejeitado; REOPEN; re-run estaciona o ciclo 2 (achado MEDIA da revisão adversarial:
        // um APPROVE atrasado do ciclo 1 NÃO pode aprovar o output do ciclo 2 que ninguém revisou).
        issues.review(companyId, issueId, ReviewDecision.REJECT, "refazer", primeiroCiclo,
                ActorType.BOARD, UUID.randomUUID());
        issues.unblock(companyId, issueId, UnblockDecision.REOPEN, ActorType.BOARD, UUID.randomUUID());
        wakeups.enqueue(companyId, agentId, issueId, "refazer");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);

        // APPROVE stale (âncora = ciclo 1, já REJECTED) → 409; a Issue segue estacionada.
        assertThatThrownBy(() -> issues.review(companyId, issueId, ReviewDecision.APPROVE, null,
                primeiroCiclo, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);

        // Ancorado no ciclo ABERTO → decide.
        UUID cicloAberto = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issueId, ReviewStatus.PENDING)
                .orElseThrow().getId();
        issues.review(companyId, issueId, ReviewDecision.APPROVE, null, cicloAberto,
                ActorType.BOARD, UUID.randomUUID());
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void rejectReopenReRunAbreNovoCicloEApproveConclui() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId); // "echo ok": o re-run sucede
        UUID issueId = parkForReview(companyId, agentId);

        // REJECT → BLOCKED; o recovery é 100% o unblock do §7 (feedback entra editando a Issue).
        issues.review(companyId, issueId, ReviewDecision.REJECT, "refazer", null, ActorType.BOARD, UUID.randomUUID());
        issues.unblock(companyId, issueId, UnblockDecision.REOPEN, ActorType.BOARD, UUID.randomUUID());
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.OPEN);

        // Re-run pelo caminho NORMAL (wakeup→dispatcher→executor, run real): risco 5 estaciona DE NOVO.
        wakeups.enqueue(companyId, agentId, issueId, "refazer pos-reject");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);

        // Novo CICLO = nova linha review (1 REJECTED do ciclo anterior + 1 PENDING nova).
        List<Review> cycles = reviews.findByCompanyIdAndIssueId(companyId, issueId);
        assertThat(cycles).hasSize(2);
        assertThat(cycles).extracting(Review::getStatus)
                .containsExactlyInAnyOrder(ReviewStatus.REJECTED, ReviewStatus.PENDING);

        issues.review(companyId, issueId, ReviewDecision.APPROVE, null, null, ActorType.BOARD, UUID.randomUUID());
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(reviews.findByCompanyIdAndStatusOrderByRequestedAt(companyId, ReviewStatus.PENDING)).isEmpty();
    }
}
