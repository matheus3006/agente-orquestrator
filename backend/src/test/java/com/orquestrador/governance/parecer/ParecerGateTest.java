package com.orquestrador.governance.parecer;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunOutcome;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import com.orquestrador.work.issue.UnblockDecision;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewDecision;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O coração da F-ORG-C (ADR-0048, D-ORG-1/2): o bloco {@code ```parecer```} na saída do run do
 * GESTOR roteia pelo {@code gestor_status} do braço — ESTÁGIO grava parecer CONSULTIVO ao lado do
 * card (autoridade intacta, o dono decide), PROMOVIDO DECIDE o ciclo com
 * {@code reviewerActorType=AGENT}. Guard de braço (subárvore) nos dois ramos, revalidado
 * fail-closed dentro do service, e dedupe por identidade de LINHA (o parecer é 1-para-1 com o
 * ciclo) — com CAS de BANCO nos dois ramos, não read-then-write: ver
 * {@link #settlesCONCORRENTESNoMesmoCiclo_soUmParecerVence_eNinguemSobrescreve}.
 */
@SpringBootTest
@ActiveProfiles("test")
class ParecerGateTest {

    @Autowired
    private ParecerGate gate;
    @Autowired
    private ParecerService parecerService;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private IssueService issues;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamService teamService;
    @Autowired
    private CompanyService companies;
    @Autowired
    private ActivityLogRepository activities;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * A mesma árvore do cenário-padrão do dirigir: Marketing (Ana gestora) ⊃ Social Media (Bruno,
     * que ENTREGOU — num SUB-time, provando que o braço é a subárvore inteira, D-ORG-3) e um Dev
     * SEPARADO (Caio, fora do braço da Ana). A issue de Bruno já está estacionada em
     * PENDING_REVIEW com o ciclo aberto; {@code runDaAna} é o run em que ela emite o parecer.
     */
    private record Cenario(UUID companyId, Team marketing, Team dev, Agent ana, Agent bruno,
                           Agent caio, Issue issueDoBruno, Review ciclo, UUID runDaAna) {
    }

    private Cenario cenario() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Team marketing = teams.save(new Team(UUID.randomUUID(), companyId, "Marketing", "growth", null));
        Team social = teams.save(new Team(UUID.randomUUID(), companyId, "Marketing", "social",
                marketing.getId()));
        Team dev = teams.save(new Team(UUID.randomUUID(), companyId, "Dev", "core", null));

        Agent ana = agents.save(new Agent(UUID.randomUUID(), companyId, "Ana", "gestora", null));
        teamService.assignAgent(companyId, ana.getId(), marketing.getId());
        teamService.setGestor(companyId, marketing.getId(), ana.getId());

        Agent bruno = agents.save(new Agent(UUID.randomUUID(), companyId, "Bruno", "executor", null));
        teamService.assignAgent(companyId, bruno.getId(), social.getId());

        Agent caio = agents.save(new Agent(UUID.randomUUID(), companyId, "Caio", "executor", null));
        teamService.assignAgent(companyId, caio.getId(), dev.getId());

        Issue issue = emRevisao(companyId, bruno.getId(), "campanha de junho");
        Review ciclo = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issue.getId(),
                ReviewStatus.PENDING).orElseThrow();
        UUID runDaAna = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, ana.getId(), null,
                RunStatus.QUEUED)).getId();
        return new Cenario(companyId, marketing, dev, ana, bruno, caio, issue, ciclo, runDaAna);
    }

    /** Issue de alto risco entregue por {@code executor} → estaciona em PENDING_REVIEW (R.2). */
    private Issue emRevisao(UUID companyId, UUID executor, String titulo) {
        UUID issueId = issues.create(companyId, titulo, null, 5).getId();
        UUID run = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, executor, issueId,
                RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, executor, run);
        issues.settleAfterRun(companyId, issueId, run, RunOutcome.success());
        return issues.get(companyId, issueId);
    }

    private void promoveAna(Cenario c) {
        teamService.promoverGestor(c.companyId(), c.marketing().getId(), ActorType.BOARD,
                UUID.randomUUID());
    }

    private static String blocoParecer(int seq, String decisao, String texto) {
        return "```parecer\n{\"issue\": " + seq + ", \"decisao\": \"" + decisao
                + "\", \"parecer\": \"" + texto + "\"}\n```\n";
    }

    private Review recarrega(Cenario c) {
        return reviews.findById(c.ciclo().getId()).orElseThrow();
    }

    private ParecerService.PedidoDeParecer pedido(Cenario c, ReviewDecision decisao) {
        return new ParecerService.PedidoDeParecer(c.issueDoBruno().getId(), c.ciclo().getId(),
                c.marketing().getId(), c.runDaAna(), decisao, "cobre o caminho triste");
    }

    private void assertAviso(Cenario c, String trecho) {
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runDaAna()))
                .as("anti-silêncio: o descarte é VISÍVEL no StepTrace")
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo("PARECER_DISCARDED");
                    assertThat(event.getDetail()).contains(trecho);
                });
    }

    // ---------- ramo ESTÁGIO: parecer consultivo, autoridade intacta ----------

    @Test
    void gestorEmEstagio_gravaParecerSemTocarNaDecisao() {
        Cenario c = cenario();

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "o teste do 409 está lá"));

        Review ciclo = recarrega(c);
        assertThat(ciclo.getParecer()).isEqualTo("APROVARIA — o teste do 409 está lá");
        assertThat(ciclo.getParecerPorAgentId()).isEqualTo(c.ana().getId());
        assertThat(ciclo.getParecerEm()).isNotNull();
        // A fronteira da fatia: opinar NÃO é decidir.
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(ciclo.getReviewerActorType()).isNull();
        assertThat(ciclo.getReviewerActorId()).isNull();
        assertThat(ciclo.getDecisionReason()).isNull();
        assertThat(ciclo.getDecidedAt()).isNull();
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);
    }

    @Test
    void parecerEmEstagio_deixaODonoDecidirDepois_comOParecerPreservado() {
        Cenario c = cenario();
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "REJEITAR", "faltou o caso vazio"));

        // O dono lê o parecer ao lado e decide o CONTRÁRIO — parecer não é voto.
        issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE,
                "aceito assim mesmo", c.ciclo().getId(), ActorType.BOARD, UUID.randomUUID());

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getReviewerActorType()).isEqualTo(ActorType.BOARD);
        assertThat(ciclo.getParecer()).isEqualTo("REJEITARIA — faltou o caso vazio");
        assertThat(ciclo.getDecisionReason()).isEqualTo("aceito assim mesmo");
    }

    @Test
    void parecerEmEstagio_emiteOEventoDeRastro() {
        Cenario c = cenario();

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "ok"));

        assertThat(activities.findByCompanyIdOrderByCreatedAtDesc(c.companyId()))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction())
                            .isEqualTo(ActivityAction.REVIEW_PARECER_REGISTRADO.name());
                    assertThat(entry.getActorType()).isEqualTo(ActorType.AGENT.name());
                    assertThat(entry.getActorId()).isEqualTo(c.ana().getId());
                });
    }

    // ---------- ramo PROMOVIDO: decide de verdade ----------

    @Test
    void gestorPromovido_decideOCicloComoAgent_eADecisaoFabricadaDizAGENTE() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "entrega redonda"));

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getReviewerActorType()).isEqualTo(ActorType.AGENT);
        assertThat(ciclo.getReviewerActorId()).isEqualTo(c.ana().getId());
        assertThat(ciclo.getDecisionReason()).isEqualTo("APROVARIA — entrega redonda");
        // Promovido DECIDE — não deixa parecer consultivo pra ninguém carimbar depois.
        assertThat(ciclo.getParecer()).isNull();
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.DONE);
        // Rastro honesto: quem decidiu foi um AGENTE, não o dono no gate (ADR-0019/D4).
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(c.companyId()))
                .anySatisfy(decision -> assertThat(decision.getOrigem())
                        .isEqualTo(DecisionOrigem.AGENTE));
    }

    @Test
    void gestorPromovidoRejeita_issueVaiParaBlockedEReabrivelPeloDono() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "REJEITAR", "sem teste do caminho triste"));

        assertThat(recarrega(c).getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.BLOCKED);

        // "Qualquer entrega segue reabrível pelo dono" (D-ORG-2) — o unblock do §7 continua valendo.
        issues.unblock(c.companyId(), c.issueDoBruno().getId(), UnblockDecision.REOPEN,
                ActorType.BOARD, UUID.randomUUID());
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.OPEN);
    }

    // ---------- o limite duro: subárvore, titularidade, auto-review ----------

    @Test
    void issueForaDaSubarvore_recusadaNosDoisRamos() {
        Cenario c = cenario();
        Issue doCaio = emRevisao(c.companyId(), c.caio().getId(), "deploy do core");

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(doCaio.getSeq(), "APROVAR", "de fora do braço"));

        assertThat(reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(), doCaio.getId(),
                ReviewStatus.PENDING).orElseThrow().getParecer()).isNull();
        assertAviso(c, "FORA do braço");

        // E o mesmo limite vale depois da promoção — promover não amplia o braço.
        promoveAna(c);
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(doCaio.getSeq(), "APROVAR", "de fora do braço"));
        assertThat(reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(), doCaio.getId(),
                ReviewStatus.PENDING)).isPresent();
    }

    @Test
    void quemNaoEGestorTitular_temOBlocoDescartadoComAviso() {
        Cenario c = cenario();
        UUID runDoBruno = runs.save(new HeartbeatRun(UUID.randomUUID(), c.companyId(),
                c.bruno().getId(), null, RunStatus.QUEUED)).getId();

        gate.handle(c.companyId(), c.bruno().getId(), runDoBruno,
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "eu mesmo aprovo"));

        assertThat(recarrega(c).getParecer()).isNull();
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(runDoBruno)).anySatisfy(event -> {
            assertThat(event.getType()).isEqualTo("PARECER_DISCARDED");
            assertThat(event.getDetail()).contains("não é gestor titular");
        });
    }

    @Test
    void gestorAssignadoNaPropriaIssue_naoSeAutoRevisa() {
        Cenario c = cenario();
        promoveAna(c);
        Issue daPropriaAna = emRevisao(c.companyId(), c.ana().getId(), "estratégia do braço");

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(daPropriaAna.getSeq(), "APROVAR", "meu trabalho ficou ótimo"));

        assertThat(reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(), daPropriaAna.getId(),
                ReviewStatus.PENDING)).isPresent();
        assertAviso(c, "auto-revisa");
    }

    // ---------- roteamento multi-braço: o menos privilegiado vence ----------

    /**
     * Gestor com DOIS braços aplicáveis (hardening do PR #50, achado 🟠). Nada no DADO impede um
     * agente de constar como gestor de um time PAI e de um DESCENDENTE, e {@code gestor_status} é
     * POR TIME: as duas subárvores contêm o entregador ⇒ com {@code .findFirst()} sobre uma query
     * SEM {@code ORDER BY}, qual braço vencia — e portanto se o gestor apenas OPINA ou DECIDE —
     * dependia da ordem arbitrária das linhas. Mesma entrada, dois níveis de autoridade.
     *
     * <p>Regra nova: vence o MENOS PRIVILEGIADO — se QUALQUER braço aplicável está em ESTÁGIO, o
     * parecer é consultivo. Fail-safe: na dúvida, mantém o humano no laço.
     *
     * <p>O estado dos 2 braços é montado direto no banco DE PROPÓSITO. Pela porta de serviço ele
     * hoje é INALCANÇÁVEL — {@code Agent.teamId} é único, {@code setGestor} exige que o gestor seja
     * MEMBRO do time, e {@code assignAgent} revoga o posto no time que o agente deixa. A cerca é
     * defesa em profundidade para o dia em que multi-time/reatribuição existirem; o que ela NÃO
     * pode é ser descrita como se já estivesse protegendo contra um caminho vivo.
     */
    @Test
    void gestorComDoisBracosAplicaveis_oMENOSPrivilegiadoVence_naoAOrdemDasLinhas() {
        // Arranjo A: o braço PAI (linha inserida primeiro) é o PROMOVIDO; o descendente, ESTÁGIO.
        Cenario a = cenario();
        Team socialA = teams.findByParentTeamId(a.marketing().getId()).getFirst();
        jdbc.update("update team set gestor_agent_id = ? where id = ?",
                a.ana().getId(), socialA.getId());
        promoveAna(a);

        gate.handle(a.companyId(), a.ana().getId(), a.runDaAna(),
                blocoParecer(a.issueDoBruno().getSeq(), "APROVAR", "vale um parecer"));

        assertThat(recarrega(a).getStatus())
                .as("ESTÁGIO em QUALQUER braço aplicável ⇒ consultivo, não decisão")
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(recarrega(a).getParecer()).isEqualTo("APROVARIA — vale um parecer");
        assertThat(issues.get(a.companyId(), a.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);

        // Arranjo B: espelhado — o DESCENDENTE é o promovido e o pai fica em ESTÁGIO. A resposta
        // tem que ser a MESMA; é isso que prova que não é a ordem das linhas que decide.
        Cenario b = cenario();
        Team socialB = teams.findByParentTeamId(b.marketing().getId()).getFirst();
        jdbc.update("update team set gestor_agent_id = ? where id = ?",
                b.ana().getId(), socialB.getId());
        teamService.promoverGestor(b.companyId(), socialB.getId(), ActorType.BOARD, UUID.randomUUID());

        gate.handle(b.companyId(), b.ana().getId(), b.runDaAna(),
                blocoParecer(b.issueDoBruno().getSeq(), "APROVAR", "vale um parecer"));

        assertThat(recarrega(b).getStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(recarrega(b).getParecer()).isEqualTo("APROVARIA — vale um parecer");
        assertThat(issues.get(b.companyId(), b.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);
    }

    /**
     * A regra do MENOS PRIVILEGIADO deixa de valer só no gate (conferência do PR #53, 🟡). Os
     * guards do service validavam o braço que RECEBEM — existe · titular · promovido · alvo na
     * subárvore — mas nenhum deles re-derivava a ESCOLHA. Um chamador que entregasse o braço
     * PROMOVIDO quando um em ESTÁGIO também se aplica passava em todas as cercas e DECIDIA o
     * ciclo, quando a régua manda opinar.
     *
     * <p>É a doutrina que o próprio código cita quatro vezes — *"guard só no gate é seguro por
     * CONVENÇÃO, não por construção"* — aplicada ao lugar onde ela mais importa: "se um humano
     * está no laço" é o último default que pode depender de o chamador ter escolhido bem.
     *
     * <p>O arranjo é o mesmo (e igualmente artificial) de
     * {@link #gestorComDoisBracosAplicaveis_oMENOSPrivilegiadoVence_naoAOrdemDasLinhas}: dois
     * braços aplicáveis são hoje INALCANÇÁVEIS pela porta de serviço. A cerca é defesa em
     * profundidade para as fatias F-ORG-E/G, que darão tools ao gestor — o "gate irmão futuro" que
     * o javadoc do {@code ApprovalService} diz cobrir.
     */
    @Test
    void chamadorEntregaOBracoPROMOVIDOComUmEmESTAGIOAplicavel_oServiceRECUSA() {
        Cenario c = cenario();
        Team social = teams.findByParentTeamId(c.marketing().getId()).getFirst();
        // Ana titular nos DOIS: o pai (Marketing) PROMOVIDO, o descendente (Social) em ESTÁGIO.
        jdbc.update("update team set gestor_agent_id = ? where id = ?",
                c.ana().getId(), social.getId());
        promoveAna(c);

        // O chamador entrega o braço PROMOVIDO — o mais privilegiado dos dois aplicáveis.
        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(c.issueDoBruno().getId(),
                        c.ciclo().getId(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "decido pelo braço mais forte")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MENOR privilégio");
        assertThat(recarrega(c).getStatus())
                .as("nada foi decidido: o humano continua no laço")
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);

        // E o braço CERTO (o em estágio) segue funcionando pela mesma régua — a cerca recusa a
        // escolha errada, não o caminho.
        assertThat(parecerService.registrarParecerDoGestor(c.companyId(), c.ana().getId(),
                new ParecerService.PedidoDeParecer(c.issueDoBruno().getId(), c.ciclo().getId(),
                        social.getId(), c.runDaAna(), ReviewDecision.APPROVE, "opino, não decido")))
                .isEqualTo(ParecerService.ResultadoDoParecer.GRAVADO);
        assertThat(recarrega(c).getParecer()).isEqualTo("APROVARIA — opino, não decido");
    }

    // ---------- bypass: chamador direto, pulando o gate ----------

    @Test
    void chamadorDiretoComGestorEmEstagio_oProprioServiceRecusaODecide() {
        Cenario c = cenario(); // Ana é titular, mas NÃO promovida

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), pedido(c, ReviewDecision.APPROVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESTÁGIO");
        assertThat(recarrega(c).getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void chamadorDiretoPorQuemNaoEGestorDoBraco_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.bruno().getId(), pedido(c, ReviewDecision.APPROVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não é o gestor titular");
        assertThat(recarrega(c).getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void chamadorDiretoComIssueForaDaSubarvore_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);
        Issue doCaio = emRevisao(c.companyId(), c.caio().getId(), "deploy do core");
        Review cicloDoCaio = reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(),
                doCaio.getId(), ReviewStatus.PENDING).orElseThrow();

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(doCaio.getId(),
                        cicloDoCaio.getId(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "de fora")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORA da subárvore");
        assertThat(reviews.findById(cicloDoCaio.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
    }

    /** O guard vale no ramo CONSULTIVO também: opinar fora do braço também é fora do braço. */
    @Test
    void chamadorDiretoDoParecerConsultivoForaDoBraco_oProprioServiceRecusa() {
        Cenario c = cenario();
        Issue doCaio = emRevisao(c.companyId(), c.caio().getId(), "deploy do core");
        Review cicloDoCaio = reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(),
                doCaio.getId(), ReviewStatus.PENDING).orElseThrow();

        assertThatThrownBy(() -> parecerService.registrarParecerDoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(doCaio.getId(),
                        cicloDoCaio.getId(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "de fora")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORA da subárvore");
        assertThat(reviews.findById(cicloDoCaio.getId()).orElseThrow().getParecer()).isNull();
    }

    /**
     * A cerca de auto-review era a ÚNICA das 5 sem teste de chamador direto ao service — justo a
     * que a fatia anterior reivindicou como descoberta própria (hardening do PR #50, achado 🟠).
     * O teste que havia (`gestorAssignadoNaPropriaIssue_naoSeAutoRevisa`) passa pelo gate, que
     * pré-checa e nem chega no service: não prova nada sobre quem chama o service direto.
     */
    @Test
    void chamadorDiretoSobreAPropriaEntrega_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);
        Issue daPropriaAna = emRevisao(c.companyId(), c.ana().getId(), "estratégia do braço");
        Review cicloDaAna = reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(),
                daPropriaAna.getId(), ReviewStatus.PENDING).orElseThrow();

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(daPropriaAna.getId(),
                        cicloDaAna.getId(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "meu trabalho ficou ótimo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto-revisa");
        assertThat(reviews.findById(cicloDaAna.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(issues.get(c.companyId(), daPropriaAna.getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);
    }

    /**
     * A LAVANDERIA (hardening do PR #50, achado 🟠): a cerca mirava no {@code assignee} ATUAL, não
     * em QUEM PRODUZIU o artefato sob revisão (o agente do run que o ciclo aponta). Os dois
     * coincidem hoje — mas por invariante que NENHUM update guardado afirma. Com um endpoint de
     * reatribuição, o gestor entrega, reatribui a um subordinado e aprova a própria entrega: a
     * cerca vê "assignee = Bruno ≠ Ana" e libera.
     *
     * <p>O estado {@code assignee ≠ entregador} é construído direto no banco DE PROPÓSITO — é
     * exatamente o que esse endpoint produziria, e uma cerca tem de valer contra o ESTADO, não
     * contra a sorte de ninguém ainda o produzir.
     */
    @Test
    void gestorAprovandoAPropriaEntregaJaREATRIBUIDA_recusadoPorQuemENTREGOU() {
        Cenario c = cenario();
        promoveAna(c);
        Issue entregaDaAna = emRevisao(c.companyId(), c.ana().getId(), "peça da própria Ana");
        Review ciclo = reviews.findByCompanyIdAndIssueIdAndStatus(c.companyId(),
                entregaDaAna.getId(), ReviewStatus.PENDING).orElseThrow();
        // O endpoint futuro de reatribuição, simulado: o assignee deixa de ser quem produziu.
        jdbc.update("update issue set assignee_agent_id = ? where id = ?",
                c.bruno().getId(), entregaDaAna.getId());

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(entregaDaAna.getId(),
                        ciclo.getId(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "aprovo a minha própria")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENTREGOU");
        assertThat(reviews.findById(ciclo.getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.PENDING);
        assertThat(issues.get(c.companyId(), entregaDaAna.getId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);
    }

    @Test
    void chamadorDiretoComAncoraDeOutroCiclo_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);

        assertThatThrownBy(() -> parecerService.decidirReviewComoGestor(c.companyId(),
                c.ana().getId(), new ParecerService.PedidoDeParecer(c.issueDoBruno().getId(),
                        UUID.randomUUID(), c.marketing().getId(), c.runDaAna(),
                        ReviewDecision.APPROVE, "âncora podre")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ancorado no ciclo");
        assertThat(recarrega(c).getStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    // ---------- dedupe estrutural do settle reexecutado ----------

    @Test
    void mesmoRunReexecutadoComProsaReformulada_naoDuplicaNemSobrescreveOParecer() {
        Cenario c = cenario();
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "primeira redação do LLM"));

        // O zumbi do lease-reaper: MESMO runId, chamada REAL nova ao LLM, prosa diferente.
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "REJEITAR", "segunda redação, bem outra"));

        assertThat(recarrega(c).getParecer()).isEqualTo("APROVARIA — primeira redação do LLM");
        assertThat(reviews.findByCompanyIdAndIssueId(c.companyId(), c.issueDoBruno().getId()))
                .hasSize(1);
        assertAviso(c, "já tinha parecer");
    }

    /**
     * O lost update do ramo ESTÁGIO (hardening do PR #50, achado 🔴 2). O teste vizinho
     * ({@link #mesmoRunReexecutadoComProsaReformulada_naoDuplicaNemSobrescreveOParecer}) chama o
     * gate 2× EM SEQUÊNCIA na mesma thread: prova o zumbi SEQUENCIAL, e passava mesmo com o
     * read-then-write puro — foi essa falsa confiança que a conferência pegou. O split-brain do
     * lease-reaper é o zumbi CONCORRENTE: duas transações leem {@code parecer IS NULL} antes de
     * qualquer commit, a segunda espera o row lock e SOBRESCREVE em silêncio, devolvendo
     * {@code true} sem o aviso "já tinha parecer".
     *
     * <p>A cerca passou a ser do BANCO ({@code and parecer is null} no UPDATE guardado): quem
     * arbitra a corrida é o {@code rows affected}, não a aplicação. Exatamente UM chamador escreve;
     * os outros recebem {@code false}, que o gate transforma em aviso contado.
     */
    @Test
    void settlesCONCORRENTESNoMesmoCiclo_soUmParecerVence_eNinguemSobrescreve() throws Exception {
        Cenario c = cenario();
        int concorrentes = 6;
        CyclicBarrier largada = new CyclicBarrier(concorrentes);
        ExecutorService pool = Executors.newFixedThreadPool(concorrentes);

        List<Future<String>> pendentes = new ArrayList<>();
        for (int i = 0; i < concorrentes; i++) {
            String texto = "redacao " + i;
            pendentes.add(pool.submit(() -> {
                largada.await(10, TimeUnit.SECONDS); // todos largam juntos: sobreposição real
                try {
                    ParecerService.ResultadoDoParecer resultado =
                            parecerService.registrarParecerDoGestor(c.companyId(),
                                    c.ana().getId(), new ParecerService.PedidoDeParecer(
                                            c.issueDoBruno().getId(), c.ciclo().getId(),
                                            c.marketing().getId(), c.runDaAna(),
                                            ReviewDecision.APPROVE, texto));
                    return resultado == ParecerService.ResultadoDoParecer.GRAVADO
                            ? "VENCEU:" + texto : "PERDEU:" + resultado;
                } catch (RuntimeException e) {
                    return "EXPLODIU:" + e;
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> saidas = new ArrayList<>();
        for (Future<String> pendente : pendentes) {
            saidas.add(pendente.get());
        }

        // Perder a corrida é resultado NORMAL (false), nunca exceção: o gate conta como aviso.
        assertThat(saidas).filteredOn(saida -> saida.startsWith("EXPLODIU")).isEmpty();
        List<String> vencedores = saidas.stream().filter(saida -> saida.startsWith("VENCEU:")).toList();
        assertThat(vencedores)
                .as("o banco arbitra a corrida: exatamente UM parecer entra")
                .hasSize(1);
        // O que ficou gravado é o do VENCEDOR — não o de quem chegou por último sobrescrevendo.
        String textoVencedor = vencedores.get(0).substring("VENCEU:".length());
        assertThat(recarrega(c).getParecer()).isEqualTo("APROVARIA — " + textoVencedor);
    }

    /**
     * A corrida dono-decide × gestor-opina no ramo ESTÁGIO (conferência dos PRs #51/#52, 🟠 1). O
     * teste vizinho ({@link #ramoPromovidoReexecutado_naoDecideDuasVezes}) só cobre o ramo
     * PROMOVIDO, que fecha por {@code where status='PENDING_REVIEW'}. Aqui a janela é entre
     * {@code cicloAbertoAncorado} e o UPDATE guardado — e ela NÃO é de nanossegundo: contém
     * {@code exigirGestorTitularComAlvoNoBraco}, que faz 4 round-trips ao banco.
     *
     * <p>O que o teste fixa é a assimetria que a ADR apresentava como simetria: {@code parecer is
     * null} guarda a DUPLICATA, não o CICLO DE VIDA. Com o dono decidindo no meio, o campo segue
     * nulo — então o guard antigo deixava passar e gravava parecer consultivo num ciclo já
     * APPROVED, devolvendo {@code true}, emitindo {@code REVIEW_PARECER_REGISTRADO} e deixando
     * {@code parecer_em > decided_at} sem aviso nenhum.
     */
    @Test
    void donoDecideNoMeioDaJanela_parecerConsultivoNaoEntraEmCicloFechado() {
        Cenario c = cenario();

        // O dono decide o ciclo — é isto que acontece DENTRO da janela, enquanto o gestor em
        // estágio ainda está passando pelas 5 cercas do guard de braço.
        issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE,
                "aprovado pelo dono antes do parecer chegar", c.ciclo().getId(),
                ActorType.BOARD, UUID.randomUUID());

        Review fechado = recarrega(c);
        assertThat(fechado.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        // A premissa que torna o guard antigo insuficiente: o campo do parecer continua NULO.
        assertThat(fechado.getParecer())
                .as("a janela é real: `parecer is null` segue verdadeiro num ciclo já decidido")
                .isNull();

        // Em transação, como em produção: o UPDATE guardado faz flush (@Modifying).
        int rows = new TransactionTemplate(transactionManager).execute(status ->
                reviews.tryRegistrarParecer(c.ciclo().getId(), c.companyId(), c.ana().getId(),
                        "APROVARIA — cheguei depois do dono"));

        assertThat(rows)
                .as("quem arbitra é o BANCO: ciclo fechado não aceita parecer, mesmo sem duplicata")
                .isZero();
        assertThat(recarrega(c).getParecer()).isNull();
        assertThat(recarrega(c).getParecerEm()).isNull();
    }

    /**
     * <b>ORDEM DE COMMIT A</b> — o lost update ESPELHADO (conferência do PR #53, 🟠 1): o parecer
     * commita e o flush da decisão do dono passa POR CIMA dele. É a forma que o PR #50 fechou,
     * virada ao contrário, e a cerca de lá NÃO alcança esta direção: quem sobrescreve aqui não é
     * o UPDATE guardado, é o <b>dirty-checking do JPA</b>. Sem {@code @DynamicUpdate} o Hibernate
     * emite UPDATE de TODAS as colunas com os valores do próprio persistence context — inclusive
     * {@code parecer = null} —, e o {@code @Id} está no CAMPO, então ele escreve por field access,
     * <b>independentemente de existir setter</b>. Remover o setter (PR #50) tornou o caminho
     * invisível ao grep, não inexistente; a cerca de verdade é {@code updatable = false} nas 3
     * colunas em {@link Review}.
     *
     * <p><b>Por que a corrida é montada no REPOSITÓRIO e não em {@code IssueService.review}</b> —
     * medido, não suposto. A janela real dentro do {@code review} é mais estreita do que parece:
     * {@code issues.tryApprove} é {@code @Modifying(clearAutomatically = true)}, então LIMPA o
     * contexto e o {@code findByCompanyIdAndIssueIdAndStatus} logo depois recarrega o ciclo FRESCO
     * — a janela só ABRE aí. E ela FECHA antes do fim do método: {@code activityLog.record} é um
     * {@code save} puro, mas o {@code fabricaDecisaoDaReview} logo em seguida faz query
     * ({@code get(companyId, issueId)}), e o AUTO-flush do Hibernate emite o UPDATE do ciclo ali,
     * tomando o row lock. Consequência prática: quando {@code review()} RETORNA a linha já está
     * flushada e travada — a primeira versão deste teste pausava ali e o gestor ficava BLOQUEADO
     * até dar timeout, provando o lock em vez do lost update. A janela verdadeira é o trecho
     * {@code SELECT do ciclo → primeiro AUTO-flush}, que não tem seam alcançável de fora.
     *
     * <p><b>Limite declarado deste teste, para não virar a próxima alegação de completude:</b> ele
     * exercita o mesmo ESTADO que a janela produz (entidade gerenciada com {@code parecer} nulo no
     * snapshot + escrita concorrente commitada + flush do JPA por cima), pela API pública do
     * repositório e da entidade — <b>não</b> pelo {@code IssueService.review}. O que ele fixa é a
     * garantia do MAPEAMENTO: nenhuma escrita JPA em {@code Review} carrega as colunas de parecer.
     * É a garantia que fecha a janela, mas quem lê isto não deve concluir que o caminho do service
     * foi dirigido nesta ordem. A ordem B abaixo dirige o service e o gate de ponta a ponta.
     */
    @Test
    void parecerCommitadoAntesDoFlushDoDono_SOBREVIVEAEscritaJpaDoCiclo() throws Exception {
        Cenario c = cenario();
        CyclicBarrier donoCarregouOCiclo = new CyclicBarrier(2);
        CyclicBarrier gestorCommitou = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<String> dono = pool.submit(() -> {
            new TransactionTemplate(transactionManager).execute(status -> {
                // Mesmo estado que o SELECT dentro do review deixa: ciclo GERENCIADO, parecer nulo
                // no snapshot. Nenhum lock ainda — SELECT puro não trava linha.
                Review ciclo = reviews.findById(c.ciclo().getId()).orElseThrow();
                try {
                    donoCarregouOCiclo.await(10, TimeUnit.SECONDS);
                    gestorCommitou.await(10, TimeUnit.SECONDS); // o parecer JÁ está no banco
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ciclo.approve(ActorType.BOARD, UUID.randomUUID(), "aprovado pelo dono");
                reviews.save(ciclo);
                return null;
            }); // ← flush do commit AQUI: é este UPDATE que apagava o parecer
            return "COMMITOU";
        });

        Future<String> gestor = pool.submit(() -> {
            donoCarregouOCiclo.await(10, TimeUnit.SECONDS);
            String resultado = parecerService.registrarParecerDoGestor(c.companyId(),
                    c.ana().getId(), pedido(c, ReviewDecision.APPROVE)).name();
            gestorCommitou.await(10, TimeUnit.SECONDS);
            return resultado;
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(dono.get()).isEqualTo("COMMITOU");
        assertThat(gestor.get())
                .as("o ciclo ainda estava PENDING no banco: o UPDATE guardado do gestor GRAVA")
                .isEqualTo("GRAVADO");

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getDecisionReason()).isEqualTo("aprovado pelo dono");
        assertThat(ciclo.getParecer())
                .as("o flush do dono NÃO pode apagar o parecer que já commitou")
                .isEqualTo("APROVARIA — cobre o caminho triste");
        assertThat(ciclo.getParecerPorAgentId()).isEqualTo(c.ana().getId());
        assertThat(ciclo.getParecerEm())
                .as("evento de auditoria dizendo que entrou + coluna vazia é o pior dos mundos")
                .isNotNull();
    }

    /**
     * <b>A corrida SEM ordem imposta</b> — o dono e 5 gestores largam JUNTOS, e o que se afirma
     * são INVARIANTES que valem em qualquer interleaving. É a extensão direta do molde de
     * {@link #settlesCONCORRENTESNoMesmoCiclo_soUmParecerVence_eNinguemSobrescreve} (6 threads +
     * {@code CyclicBarrier}) com o DONO entrando na disputa, e existe por causa do 🟡 3 da
     * conferência do PR #53: o teste que validava o guard novo era sequencial e chamava o
     * repositório direto, pulando o service — provava o predicado SQL e <b>nunca exercitava a
     * reação do chamador</b>, que é justamente onde morava o 🟡 2.
     *
     * <p>Nada aqui assume qual thread vence: as asserções são consistência entre o que cada
     * chamador RELATOU e o estado final observado. É o que torna o teste não-flaky — e é o que
     * pega uma mensagem mentirosa, porque um relato incoerente com o estado final falha
     * independentemente de quem chegou primeiro.
     *
     * <p><b>O que esta corrida NÃO cobre — medido, não suposto.</b> Uma sonda temporária imprimiu
     * os relatos em 6 execuções: em todas, o desfecho foi 1 {@code GRAVADO} + 4
     * {@code JA_TINHA_PARECER}, e o dono commitou por ÚLTIMO nas 6. A razão é estrutural: a
     * transação dos gestores é curta (guard + 1 UPDATE) e a do dono é longa
     * ({@code tryApprove} + SELECT + {@code activityLog} + a fábrica de Decisão), então os
     * pareceres terminam antes de o flush do dono tomar o row lock. <b>Logo este teste não
     * exercita {@code DONO_DECIDIU_NO_MEIO}</b> — quem exercita é
     * {@link #donoSEGURAORowLock_gestorPassaNoGuardEDescobreNoUPDATE_avisoDizQueCHEGOUTARDE},
     * que força a ordem pelo lock em vez de torcer pelo escalonador.
     */
    @Test
    void donoEGestoresLARGAMJUNTOS_todoRelatoBateComOEstadoFinal() throws Exception {
        Cenario c = cenario();
        int gestores = 5;
        CyclicBarrier largada = new CyclicBarrier(gestores + 1);
        ExecutorService pool = Executors.newFixedThreadPool(gestores + 1);

        List<Future<String>> pendentes = new ArrayList<>();
        for (int i = 0; i < gestores; i++) {
            String texto = "redacao " + i;
            pendentes.add(pool.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                try {
                    return parecerService.registrarParecerDoGestor(c.companyId(), c.ana().getId(),
                            new ParecerService.PedidoDeParecer(c.issueDoBruno().getId(),
                                    c.ciclo().getId(), c.marketing().getId(), c.runDaAna(),
                                    ReviewDecision.APPROVE, texto)).name() + ":" + texto;
                } catch (RuntimeException recusa) {
                    // Recusa do guard (o ciclo fechou antes de o gestor chegar) é desfecho NORMAL
                    // deste canal — o gate a transforma em aviso contado.
                    return "RECUSADO:" + texto;
                }
            }));
        }
        Future<String> dono = pool.submit(() -> {
            largada.await(10, TimeUnit.SECONDS);
            issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE,
                    "o dono decide no meio da chuva de pareceres", c.ciclo().getId(),
                    ActorType.BOARD, UUID.randomUUID());
            return "DECIDIU";
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(dono.get()).isEqualTo("DECIDIU");
        List<String> relatos = new ArrayList<>();
        for (Future<String> pendente : pendentes) {
            relatos.add(pendente.get());
        }

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus())
                .as("o dono sempre leva a decisão: nenhum parecer consultivo a disputa")
                .isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getDecisionReason()).isEqualTo("o dono decide no meio da chuva de pareceres");

        List<String> gravaram = relatos.stream().filter(r -> r.startsWith("GRAVADO:")).toList();
        assertThat(gravaram).as("o banco arbitra: no máximo UM parecer entra").hasSizeLessThanOrEqualTo(1);

        // O invariante do 🟠 1, agora sob corrida REAL e não sob ordem imposta: se alguém gravou,
        // o texto dele TEM que estar lá no fim — o flush da decisão do dono não o apaga.
        if (gravaram.size() == 1) {
            String texto = gravaram.getFirst().substring("GRAVADO:".length());
            assertThat(ciclo.getParecer())
                    .as("quem relatou GRAVADO tem que sobreviver ao flush do dono")
                    .isEqualTo("APROVARIA — " + texto);
        } else {
            assertThat(ciclo.getParecer())
                    .as("ninguém relatou GRAVADO ⇒ não pode haver parecer no banco")
                    .isNull();
        }

        // O invariante do 🟡 2: nenhum relato pode afirmar duplicata que o estado final desmente.
        if (ciclo.getParecer() == null) {
            assertThat(relatos)
                    .as("sem parecer no banco, ninguém pode ter sido barrado por 'já tinha parecer'")
                    .noneMatch(relato -> relato.startsWith("JA_TINHA_PARECER"));
        }
        assertThat(relatos)
                .as("a re-leitura tem que explicar toda recusa: CICLO_MUDOU é o balde do inexplicado")
                .noneMatch(relato -> relato.startsWith("CICLO_MUDOU"));
    }

    /**
     * A causa NOVA de {@code rows == 0}, exercitada pelo caminho por onde ela acontece de verdade:
     * o gestor passa pelo guard com o ciclo ainda PENDING e só DESCOBRE no UPDATE que o dono
     * fechou. É este o desfecho que o 🟡 2 da conferência do PR #53 relatava errado — o aviso
     * dizia *"já tinha parecer neste ciclo — mantido o primeiro"*, afirmando que existe um parecer
     * (não existe) e que ele foi mantido (nada foi mantido).
     *
     * <p><b>A ordem é forçada pelo ROW LOCK, não pelo escalonador.</b> O dono roda
     * {@code IssueService.review} dentro de uma transação que este teste segura: como
     * {@code fabricaDecisaoDaReview} faz query, o AUTO-flush do Hibernate já emitiu o
     * {@code update review} quando {@code review()} retorna — a linha está TRAVADA e o commit ainda
     * não veio. O gestor então entra: o SELECT do guard lê o estado COMMITADO (ainda PENDING, MVCC)
     * e passa; o {@code tryRegistrarParecer} bate no lock e BLOQUEIA. O dono commita, o gestor
     * destrava e o {@code and status = 'PENDING'} já não casa ⇒ 0 linhas, com o ciclo APPROVED.
     *
     * <p><b>A dependência de tempo, dita por extenso — e ela tem DOIS lados.</b> O dono segura a
     * linha por 400ms, e esse número está espremido entre dois limites reais:
     * <ul>
     *   <li><b>piso</b> — o gestor precisa atravessar o guard (4 round-trips de H2 em memória,
     *       sub-milissegundo) e chegar ao UPDATE antes do commit do dono. Se não chegasse, o guard
     *       levantaria "não tem ciclo de review aberto" e a asserção falharia RUIDOSAMENTE;</li>
     *   <li><b>teto</b> — o {@code LOCK_TIMEOUT} do H2 (default 1s; o
     *       {@code application-test.yml} não o configura). Uma versão anterior deste teste segurava
     *       a linha por 2s e o gestor MORRIA com {@code CannotAcquireLockException} em vez de
     *       esperar: verde sozinho, vermelho na classe inteira. O teto é real e foi medido, não
     *       suposto.</li>
     * </ul>
     * 400ms fica ~400× acima do piso e ~2,5× abaixo do teto. Não é determinismo — é uma folga
     * declarada, e os dois modos de falha são ruidosos.
     */
    @Test
    void donoSEGURAORowLock_gestorPassaNoGuardEDescobreNoUPDATE_avisoDizQueCHEGOUTARDE()
            throws Exception {
        Cenario c = cenario();
        CyclicBarrier donoTravouALinha = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> dono = pool.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE,
                            "decidi com o gestor a caminho", c.ciclo().getId(), ActorType.BOARD,
                            UUID.randomUUID());
                    try {
                        // Aqui o UPDATE do ciclo JÁ foi flushado (a fábrica de Decisão fez query):
                        // a linha está travada e o commit ainda não aconteceu.
                        donoTravouALinha.await(10, TimeUnit.SECONDS);
                        Thread.sleep(400); // ver a folga de dois lados no javadoc acima
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
        Future<String> gestor = pool.submit(() -> {
            donoTravouALinha.await(10, TimeUnit.SECONDS);
            return parecerService.registrarParecerDoGestor(c.companyId(), c.ana().getId(),
                    pedido(c, ReviewDecision.APPROVE)).name();
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        dono.get();
        assertThat(gestor.get())
                .as("passou no guard com o ciclo PENDING e descobriu no UPDATE: causa NOVA")
                .isEqualTo("DONO_DECIDIU_NO_MEIO");

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getParecer())
                .as("não há duplicata nenhuma para o operador caçar")
                .isNull();
        assertThat(ParecerGate.motivoDaRecusa(ParecerService.ResultadoDoParecer.DONO_DECIDIU_NO_MEIO))
                .as("o aviso que o operador lê nesta causa não pode inventar um primeiro parecer")
                .doesNotContain("já tinha parecer", "mantido o primeiro")
                .contains("não há duplicata");
    }

    /**
     * <b>ORDEM DE COMMIT B</b> — o dono commita PRIMEIRO e o gestor chega tarde. Aqui não há o que
     * salvar: o UPDATE guardado casa 0 linhas por {@code and status = 'PENDING'} (a cerca do PR
     * #53). O que este teste fixa é a REAÇÃO DO CHAMADOR, que nenhum teste exercitava — e era
     * exatamente por isso que o 🟡 2 escapou: o aviso dizia *"já tinha parecer neste ciclo —
     * mantido o primeiro"*, <b>duas afirmações falsas</b> nesta causa (não existe parecer, e nada
     * foi mantido). Por isso a corrida é dirigida pelo GATE, de ponta a ponta, e não pelo
     * repositório: o aviso só existe no gate.
     *
     * <p>As duas threads são reais; o {@code CyclicBarrier} fixa a ordem de commit. O dono chega a
     * commitar ANTES de o gestor começar — o oposto da ordem A.
     */
    @Test
    void donoCommitaPRIMEIRO_gestorChegaTarde_eOAvisoNaoInventaDuplicata() throws Exception {
        Cenario c = cenario();
        CyclicBarrier donoCommitou = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> dono = pool.submit(() -> {
            issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE,
                    "decidi antes do parecer chegar", c.ciclo().getId(), ActorType.BOARD,
                    UUID.randomUUID());
            return donoCommitou.await(10, TimeUnit.SECONDS);
        });
        Future<?> gestor = pool.submit(() -> {
            donoCommitou.await(10, TimeUnit.SECONDS);
            gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                    blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "cheguei tarde"));
            return null;
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        dono.get();
        gestor.get();

        Review ciclo = recarrega(c);
        assertThat(ciclo.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(ciclo.getParecer())
                .as("ciclo fechado não aceita parecer consultivo — nada foi gravado")
                .isNull();
        assertThat(ciclo.getParecerEm()).isNull();
        // O coração deste teste: o aviso não pode mandar o operador caçar duplicata inexistente.
        // Nesta ordem quem barra é o PRÓPRIO gate — o ciclo já não está PENDING, some da busca
        // antes de o service ser chamado —, e a frase dele já era honesta. O que estava errado era
        // a frase do ramo rows==0, exercitada na corrida de 6 threads.
        assertAviso(c, "não tem ciclo de review aberto");
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runDaAna()))
                .as("a frase antiga afirmava duas coisas falsas nesta causa")
                .noneSatisfy(event -> assertThat(event.getDetail()).contains("mantido o primeiro"));
    }

    @Test
    void ramoPromovidoReexecutado_naoDecideDuasVezes() {
        Cenario c = cenario();
        promoveAna(c);
        String bloco = blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "entrega redonda");
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(), bloco);

        // 2ª passada: o ciclo já não está PENDING, então nem chega no service.
        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "REJEITAR", "outra redação do zumbi"));

        assertThat(recarrega(c).getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(issues.get(c.companyId(), c.issueDoBruno().getId()).getStatus())
                .isEqualTo(IssueStatus.DONE);
        assertAviso(c, "não tem ciclo de review aberto");
    }

    // ---------- fail-SOFT de verdade: nada escapa do gate ----------

    /**
     * O TOCTOU dono×gestor (hardening do PR #50, achado 🟠). Se o dono decide o ciclo ENTRE a
     * resolução do gate e a chamada do service, o service levanta — e ninguém pegava: o gate não
     * tinha try/catch nem é {@code @Transactional}, o {@code AdapterRunExecutor} idem, e o
     * {@code execute} chama {@code runToOutcome} ANTES do {@code settleAfterRun} ⇒ a exceção
     * PULAVA o settle. A issue ficava {@code IN_PROGRESS} com {@code execution_lock_run_id} de um
     * run morto, e {@code tryCheckout} exige essa coluna null-ou-igual. E contradizia o fail-SOFT
     * que o próprio gate declara.
     *
     * <p><b>Emendado pela Fatia I-bis:</b> este javadoc dizia que <i>não existe reaper para essa
     * coluna</i> e que a issue ficava impegável PARA SEMPRE. O {@code LockOrfaoReaper} existe desde
     * a Fatia I e, com o ponto cego fechado, alcança também o run sem linha — o dano virou uma
     * Issue estacionada em {@code BLOCKED} pela varredura, não mais um bloqueio permanente. O que
     * se perde sem este try/catch continua real: o motivo REAL do incidente, que só a fonte sabe.
     *
     * <p>Aqui o estado que a corrida DEIXA é construído direto (issue fora de
     * {@code PENDING_REVIEW} com o ciclo PENDING para trás) em vez de perseguir a janela de tempo:
     * é o mesmo caminho de código, sem teste dependente de timing. O bloco malformado vem ANTES de
     * propósito — prova que os avisos já acumulados chegam ao StepTrace em vez de se perderem
     * junto com a exceção.
     */
    @Test
    void servicoLevantandoNoMeioDoLote_naoEscapaDoGate_eOsAvisosAcumuladosSOBREVIVEM() {
        Cenario c = cenario();
        promoveAna(c);
        jdbc.update("update issue set status = 'DONE' where id = ?", c.issueDoBruno().getId());

        assertThatCode(() -> gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                "```parecer\n{\"issue\": 4242}\n```\n"
                        + blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "o dono decidiu antes")))
                .as("fail-SOFT: um ciclo que virou no meio do caminho não derruba o run do gestor")
                .doesNotThrowAnyException();

        assertAviso(c, "malformado");
        assertAviso(c, "recusado");
    }

    // ---------- higiene do canal: cap, eco, malformado ----------

    @Test
    void ecoIdenticoNoMesmoReply_colapsaEmUmParecerSo() {
        Cenario c = cenario();
        String bloco = blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "ok");

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(), bloco + bloco);

        assertThat(recarrega(c).getParecer()).isEqualTo("APROVARIA — ok");
        assertAviso(c, "colapsada");
    }

    @Test
    void acimaDoTeto_osExcedentesViramAviso() {
        Cenario c = cenario();
        StringBuilder reply = new StringBuilder();
        for (int i = 0; i < ParecerGate.CAP_PARECERES_POR_RUN + 1; i++) {
            Issue extra = emRevisao(c.companyId(), c.bruno().getId(), "entrega " + i);
            reply.append(blocoParecer(extra.getSeq(), "APROVAR", "parecer " + i));
        }

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(), reply.toString());

        List<Review> comParecer = reviews.findAll().stream()
                .filter(review -> c.companyId().equals(review.getCompanyId()))
                .filter(Review::temParecer).toList();
        assertThat(comParecer).hasSize(ParecerGate.CAP_PARECERES_POR_RUN);
        assertAviso(c, "além do teto");
    }

    @Test
    void blocoMalformado_naoDerrubaORunEViraAvisoContado() {
        Cenario c = cenario();

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                "```parecer\n{\"issue\": " + c.issueDoBruno().getSeq() + "}\n```\n");

        assertThat(recarrega(c).getParecer()).isNull();
        assertAviso(c, "malformado");
    }

    @Test
    void issueSemCicloAberto_viraAvisoEmVezDeExplodir() {
        Cenario c = cenario();
        issues.review(c.companyId(), c.issueDoBruno().getId(), ReviewDecision.APPROVE, null,
                c.ciclo().getId(), ActorType.BOARD, UUID.randomUUID());

        gate.handle(c.companyId(), c.ana().getId(), c.runDaAna(),
                blocoParecer(c.issueDoBruno().getSeq(), "APROVAR", "atrasado"));

        assertAviso(c, "não tem ciclo de review aberto");
    }
}
