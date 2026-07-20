package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.BadRequestException;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.governance.parecer.ParecerGate;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A REABERTURA de entrega aprovada (ADR-0049 — decisão do dono, 2026-07-20), fechando o achado
 * 🔴 1 da conferência do PR #50: {@code REJECT → BLOCKED} sempre foi reabrível, mas
 * {@code APPROVE → DONE} não era — {@code DONE} não aparecia como estado-ORIGEM em nenhum UPDATE
 * guardado, então era SINK. Um gestor promovido podia carimbar entrega ruim e fechar a issue
 * <b>sem nenhum humano tê-la visto como PENDING</b>, sem recurso.
 *
 * <p>Isso contradizia a <b>D-ORG-2</b> ("gestor decide sozinho… e qualquer entrega reabrível pelo
 * dono"): a reabertura era a VÁLVULA que justificava delegar decisão a um agente. Sem ela,
 * promover é porta de mão única e o cálculo de risco da promoção muda.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReaberturaDeEntregaAprovadaTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private ParecerGate gate;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamService teamService;
    @Autowired
    private CompanyService companies;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private ActivityLogRepository activities;
    @Autowired
    private DecisionRepository decisions;

    private record Cenario(UUID companyId, Agent ana, Agent bruno, Issue issue, Review ciclo,
                           UUID runDaAna) {
    }

    /**
     * Ana gestora PROMOVIDA do Marketing; Bruno entrega no sub-time e a issue estaciona em
     * PENDING_REVIEW. É o cenário exato do risco: quem decide o ciclo é um agente.
     */
    private Cenario cenarioComGestoraPromovida() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Team marketing = teams.save(new Team(UUID.randomUUID(), companyId, "Marketing", "growth", null));
        Team social = teams.save(new Team(UUID.randomUUID(), companyId, "Marketing", "social",
                marketing.getId()));

        Agent ana = agents.save(new Agent(UUID.randomUUID(), companyId, "Ana", "gestora", null));
        teamService.assignAgent(companyId, ana.getId(), marketing.getId());
        teamService.setGestor(companyId, marketing.getId(), ana.getId());
        teamService.promoverGestor(companyId, marketing.getId(), ActorType.BOARD, UUID.randomUUID());

        Agent bruno = agents.save(new Agent(UUID.randomUUID(), companyId, "Bruno", "executor", null));
        teamService.assignAgent(companyId, bruno.getId(), social.getId());

        UUID issueId = issues.create(companyId, "campanha de junho", null, 5).getId();
        UUID runDoBruno = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, bruno.getId(),
                issueId, RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, bruno.getId(), runDoBruno);
        issues.settleAfterRun(companyId, issueId, runDoBruno, RunOutcome.success());

        Review ciclo = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issueId,
                ReviewStatus.PENDING).orElseThrow();
        UUID runDaAna = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, ana.getId(), null,
                RunStatus.QUEUED)).getId();
        return new Cenario(companyId, ana, bruno, issues.get(companyId, issueId), ciclo, runDaAna);
    }

    /** O gestor promovido APROVA pelo canal real (bloco ```parecer```) — a issue fecha em DONE. */
    private Cenario aprovadaPelaGestora() {
        Cenario c = cenarioComGestoraPromovida();
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                "```parecer\n{\"issue\": " + c.issue().getSeq()
                        + ", \"decisao\": \"APROVAR\", \"parecer\": \"entrega redonda\"}\n```\n");
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .as("pré-condição do cenário: o gestor promovido fechou a issue sozinho")
                .isEqualTo(IssueStatus.DONE);
        return c;
    }

    @Test
    void donoReabreEntregaQueOGestorPromovidoAprovou_eElaVoltaAoJogo() {
        Cenario c = aprovadaPelaGestora();

        issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(),
                "o teste do caminho triste não existe — passou batido", ActorType.BOARD,
                UUID.randomUUID());

        // DONE deixou de ser sink: a entrega aceita volta para BLOCKED...
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .isEqualTo(IssueStatus.BLOCKED);
        // ...e dali o unblock do §7, que já existia, dá a escolha entre refazer e encerrar.
        issues.unblock(c.companyId(), c.issue().getId(), UnblockDecision.REOPEN, ActorType.BOARD,
                UUID.randomUUID());
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .isEqualTo(IssueStatus.OPEN);
    }

    /**
     * O rastro do gestor NÃO é apagado para reabrir. Voltar o ciclo a PENDING apagaria o carimbo de
     * que ELE decidiu — e o rastro é a metade da D-ORG-2 que paga pela outra. A reabertura é fato
     * SOMADO por cima.
     */
    @Test
    void reabrir_naoApagaOCarimboDoGestorNoCiclo() {
        Cenario c = aprovadaPelaGestora();

        issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(), "passou batido",
                ActorType.BOARD, UUID.randomUUID());

        Review ciclo = reviews.findById(c.ciclo().getId()).orElseThrow();
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getReviewerActorType()).isEqualTo(ActorType.AGENT);
        assertThat(ciclo.getReviewerActorId()).isEqualTo(c.ana().getId());
        assertThat(ciclo.getDecidedAt()).isNotNull();
        assertThat(activities.findByCompanyIdOrderByCreatedAtDesc(c.companyId()))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo(ActivityAction.ISSUE_REABERTA.name());
                    assertThat(entry.getActorType()).isEqualTo(ActorType.BOARD.name());
                });
    }

    /**
     * A reabertura vira Decisão que SUPERSEDE a do carimbo desfeito. Não basta o activity_log: a
     * Decisão é o que o executor LÊ na dieta do re-run (R19-A) — um "[APPROVE REVIEW] #N" sem
     * qualificação seguiria dizendo a todo agente futuro que a entrega foi aceita.
     */
    @Test
    void reabrir_fabricaDecisaoDoDONOQueSupersedeACarimbadaPeloGestor() {
        Cenario c = aprovadaPelaGestora();
        List<Decision> antes = decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(c.companyId(),
                c.issue().getId());
        Decision doGestor = antes.stream()
                .filter(decisao -> decisao.getOrigem() == DecisionOrigem.AGENTE)
                .reduce((primeira, ultima) -> ultima).orElseThrow();

        issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(),
                "faltou o caso vazio, e o gestor não viu", ActorType.BOARD, UUID.randomUUID());

        List<Decision> depois = decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(c.companyId(),
                c.issue().getId());
        assertThat(depois).hasSize(antes.size() + 1);
        Decision daReabertura = depois.getLast();
        assertThat(daReabertura.getOrigem())
                .as("quem DESFEZ foi o dono, mesmo que quem carimbou tenha sido o gestor")
                .isEqualTo(DecisionOrigem.DONO_NO_GATE);
        assertThat(daReabertura.getOQueMudou()).startsWith("[REABERTURA] #" + c.issue().getSeq());
        assertThat(daReabertura.getRacional()).isEqualTo("faltou o caso vazio, e o gestor não viu");
        assertThat(daReabertura.getSupersedesId()).isEqualTo(doGestor.getId());
        // A do gestor continua LÁ, intacta — supersede não é apagar.
        assertThat(decisions.findById(doGestor.getId())).isPresent();
    }

    /**
     * Board-only revalidado DENTRO do service, não só no controller (lição do hardening item 2 do
     * PR #49). Um agente que alcançasse este método reabriria a própria entrega reprovada — o
     * inverso exato da cerca de auto-review.
     */
    @Test
    void agenteNaoReabre_mesmoChamandoOServiceDireto() {
        Cenario c = aprovadaPelaGestora();

        assertThatThrownBy(() -> issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(),
                "quero a minha de volta", ActorType.AGENT, c.ana().getId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("board");
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .isEqualTo(IssueStatus.DONE);
    }

    /** O motivo é a contra-evidência que informa se o gestor segue promovido — sem ele, não passa. */
    @Test
    void reaberturaSemMotivo_eRecusadaAntesDeQualquerEfeito() {
        Cenario c = aprovadaPelaGestora();

        assertThatThrownBy(() -> issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(),
                "   ", ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("motivo");
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .isEqualTo(IssueStatus.DONE);
    }

    /** Transição guardada: reabrir só vale sobre entrega APROVADA (DONE), nunca sobre outra coisa. */
    @Test
    void reabrirIssueQueNaoEstaDone_da409() {
        Cenario c = cenarioComGestoraPromovida(); // segue em PENDING_REVIEW, ninguém decidiu

        assertThatThrownBy(() -> issues.reabrirEntregaAprovada(c.companyId(), c.issue().getId(),
                "cedo demais", ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("nao esta DONE");
        assertThat(issues.get(c.companyId(), c.issue().getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);
    }
}
