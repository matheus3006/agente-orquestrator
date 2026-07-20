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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O coração da F-ORG-C (ADR-0048, D-ORG-1/2): o bloco {@code ```parecer```} na saída do run do
 * GESTOR roteia pelo {@code gestor_status} do braço — ESTÁGIO grava parecer CONSULTIVO ao lado do
 * card (autoridade intacta, o dono decide), PROMOVIDO DECIDE o ciclo com
 * {@code reviewerActorType=AGENT}. Guard de braço (subárvore) nos dois ramos, revalidado
 * fail-closed dentro do service, e dedupe ESTRUTURAL do settle reexecutado — sem fingerprint,
 * porque o parecer é 1-para-1 com o ciclo.
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
