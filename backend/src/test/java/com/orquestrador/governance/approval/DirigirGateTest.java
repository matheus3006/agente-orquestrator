package com.orquestrador.governance.approval;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O coração da fatia dirigir-sem-gate (ADR-0047, D-DSG-1..8): o bloco {@code ```dirigir```} na
 * saída do run do GESTOR roteia pelo {@code gestor_status} do braço — PROMOVIDO → Approval que
 * NASCE {@code APPROVED} (a primeira da casa) + issue direto com selo; ESTÁGIO → PENDING no gate
 * e o APPROVE do dono dispara pelo {@code case DIRECT_ISSUE} do dispatch (o teste desse case é
 * OBRIGATÓRIO: o switch é statement sem default — sem o case, aprovar compila e vira no-op
 * SILENCIOSO). Guard de braço (subárvore) nos DOIS sentidos e dedupe do settle reexecutado.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirigirGateTest {

    @Autowired
    private DirigirGate gate;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private ApprovalRepository approvals;
    @Autowired
    private IssueRepository issues;
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
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;

    /**
     * A árvore do cenário-padrão (o exemplo do grill): Marketing (Ana gestora) ⊃ Social Media
     * (Bruno, o alvo — num SUB-time, provando que o braço é a subárvore inteira, D-ORG-3) e um
     * time Dev/core SEPARADO (Caio — fora do braço da Ana).
     */
    private record Cenario(UUID companyId, Team marketing, Team social, Agent ana, Agent bruno,
                           Agent caio, UUID runId) {
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

        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, ana.getId(), null,
                RunStatus.QUEUED)).getId();
        return new Cenario(companyId, marketing, social, ana, bruno, caio, runId);
    }

    private void promoveAna(Cenario c) {
        teamService.promoverGestor(c.companyId(), c.marketing().getId(), ActorType.BOARD,
                UUID.randomUUID());
    }

    private static String blocoDirigir(String alvo, String titulo) {
        return "```dirigir\n{\"alvo\": \"" + alvo + "\", \"titulo\": \"" + titulo
                + "\", \"descricao\": \"detalhe\", \"risco\": 2, \"racional\": \"pauta fria e o "
                + alvo + " domina o assunto\"}\n```\n";
    }

    private List<Approval> approvalsDa(UUID companyId) {
        return approvals.findAll().stream()
                .filter(approval -> approval.getCompanyId().equals(companyId)).toList();
    }

    private List<Issue> issuesDa(UUID companyId) {
        return issues.findByCompanyId(companyId);
    }

    @Test
    void gestorPromovidoDirige_approvalNasceAprovada_issueNasceComSeloEEventoConsumido() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                "Fechei a análise.\n\n" + blocoDirigir("Bruno", "Calendário de posts"));

        // O cartão nasce-decidido (D-DSG-3): nunca PENDING, decidido pela PRÓPRIA gestora.
        Approval cartao = approvalsDa(c.companyId()).stream().findFirst().orElseThrow();
        assertThat(cartao.getActionType()).isEqualTo(ApprovalActionType.DIRECT_ISSUE);
        assertThat(cartao.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(cartao.getDecidedByActorType()).isEqualTo(ActorType.AGENT);
        assertThat(cartao.getDecidedByActorId()).isEqualTo(c.ana().getId());
        assertThat(cartao.getRequestedByAgentId()).isEqualTo(c.ana().getId());
        assertThat(cartao.getRunId()).isEqualTo(c.runId());
        assertThat(cartao.getRacional()).contains("pauta fria");
        assertThat(cartao.getDecisionReason()).isNull();
        assertThat(cartao.getDecidedAt()).isNotNull();

        // A issue pousa direto na mesa do Bruno (D-DSG-5) COM o selo (D-DSG-6).
        Issue issue = issuesDa(c.companyId()).stream().findFirst().orElseThrow();
        assertThat(issue.getTitle()).isEqualTo("Calendário de posts");
        assertThat(issue.getAssigneeAgentId()).isEqualTo(c.bruno().getId());
        assertThat(issue.getRiskLevel()).isEqualTo(2);
        assertThat(issue.getDirigidaPorGestorAgentId()).isEqualTo(c.ana().getId());

        // A reserva da F-ORG-B finalmente CONSUMIDA: ISSUE_DIRECIONADA_SEM_GATE, ator = a gestora.
        ActivityLog evento = activities.findByCompanyIdOrderByCreatedAtDesc(c.companyId()).stream()
                .filter(log -> "ISSUE_DIRECIONADA_SEM_GATE".equals(log.getAction()))
                .findFirst().orElseThrow();
        assertThat(evento.getActorId()).isEqualTo(c.ana().getId());
        assertThat(evento.getEntityId()).isEqualTo(issue.getId());
        assertThat(evento.getDetail()).contains(cartao.getId().toString())
                .contains(c.bruno().getId().toString()).contains(c.marketing().getId().toString());
    }

    @Test
    void gestorEmEstagioDirige_viraPendingNoGate_semIssueSemSelo() {
        Cenario c = cenario(); // Ana NÃO promovida: gestor_status = ESTAGIO

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                blocoDirigir("Bruno", "Calendário de posts"));

        Approval cartao = approvalsDa(c.companyId()).stream().findFirst().orElseThrow();
        assertThat(cartao.getActionType()).isEqualTo(ApprovalActionType.DIRECT_ISSUE);
        assertThat(cartao.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(cartao.getDecidedByActorType()).isNull();
        assertThat(issuesDa(c.companyId())).as("estágio NÃO cria issue sem o dono").isEmpty();
    }

    /**
     * O TESTE OBRIGATÓRIO do case DIRECT_ISSUE em dispatchAction (conferência, item 3): o switch
     * é statement SEM default — sem o case, este APPROVE compilaria, salvaria a Approval como
     * APPROVED e criaria ZERO issue, em silêncio. O alvo é o AGENTE ESPECÍFICO do bloco (Bruno),
     * NÃO o arquiteto do time (provado apontando um arquiteto diferente no time do alvo).
     */
    @Test
    void aprovarCartaoDoEstagio_dispatchCriaIssueNoAgenteEspecifico_semSelo() {
        Cenario c = cenario();
        // Um arquiteto ≠ Bruno no time do alvo: se o dispatch caísse no molde CREATE_ISSUE
        // (arquiteto-do-time), a issue nasceria na mesa errada.
        Agent arquiteta = agents.save(new Agent(UUID.randomUUID(), c.companyId(), "Wanda",
                "arquiteta", null));
        teamService.assignAgent(c.companyId(), arquiteta.getId(), c.social().getId());
        teamService.setArquiteto(c.companyId(), c.social().getId(), arquiteta.getId());

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                blocoDirigir("Bruno", "Calendário de posts"));
        Approval pendente = approvalsDa(c.companyId()).stream().findFirst().orElseThrow();
        assertThat(pendente.getStatus()).isEqualTo(ApprovalStatus.PENDING);

        approvalService.decide(c.companyId(), pendente.getId(), ApprovalDecision.APPROVE,
                "o dono viu como ela dirige", null, ActorType.BOARD, UUID.randomUUID());

        Issue issue = issuesDa(c.companyId()).stream().findFirst()
                .orElseThrow(() -> new AssertionError("o case DIRECT_ISSUE do dispatch sumiu:"
                        + " APPROVE virou no-op silencioso (nenhuma issue criada)"));
        assertThat(issue.getTitle()).isEqualTo("Calendário de posts");
        assertThat(issue.getAssigneeAgentId())
                .as("o alvo é o agente do bloco, nunca o arquiteto-do-time")
                .isEqualTo(c.bruno().getId())
                .isNotEqualTo(arquiteta.getId());
        assertThat(issue.getDirigidaPorGestorAgentId())
                .as("ramo estágio passou pelo gate: SEM selo (D-DSG-6)")
                .isNull();
    }

    @Test
    void alvoForaDoBraco_rejeitadoComAvisoVisivel_nadaCriado() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                blocoDirigir("Caio", "Refatorar o core"));

        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runId()))
                .as("anti-silêncio: o descarte é VISÍVEL no StepTrace")
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo("DIRIGIR_DISCARDED");
                    assertThat(event.getDetail()).contains("FORA do braço");
                });
    }

    @Test
    void alvoDentroDoSubtimeDoBraco_passa_alvoDeOutraArvore_rejeita() {
        // O guard nos DOIS sentidos numa tacada: Bruno (sub-time do braço) passa; Caio não.
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                blocoDirigir("Bruno", "Pauta da semana") + "\n" + blocoDirigir("Caio", "Fora"));

        assertThat(approvalsDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId()).get(0).getAssigneeAgentId()).isEqualTo(c.bruno().getId());
    }

    @Test
    void quemNaoEGestorNaoDirige_nadaCriado() {
        Cenario c = cenario();
        UUID runDoBruno = runs.save(new HeartbeatRun(UUID.randomUUID(), c.companyId(),
                c.bruno().getId(), null, RunStatus.QUEUED)).getId();

        gate.handle(c.companyId(), c.bruno().getId(), runDoBruno,
                blocoDirigir("Caio", "Golpe de estado"));

        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(runDoBruno))
                .anySatisfy(event -> assertThat(event.getDetail()).contains("não é gestor"));
    }

    /** O TESTE OBRIGATÓRIO do dedupe (conferência, item 7): settle reexecutado não duplica. */
    @Test
    void settleReexecutadoComOMesmoRun_naoDuplicaCartaoNemIssue() {
        Cenario c = cenario();
        promoveAna(c);
        String reply = blocoDirigir("Bruno", "Calendário de posts");

        gate.handle(c.companyId(), c.ana().getId(), c.runId(), reply);
        gate.handle(c.companyId(), c.ana().getId(), c.runId(), reply); // o reaper re-liquidou

        assertThat(approvalsDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId())).hasSize(1);
    }

    /**
     * O TESTE do hardening item 1 (fingerprint estrutural): o zumbi do lease reaper re-executa o
     * MESMO runId com uma chamada REAL nova ao LLM — que REFORMULA titulo/descricao/racional
     * (LLM não é determinístico). Com fingerprint de conteúdo, as duas linhas seriam
     * "legitimamente" diferentes e uma 2ª issue nasceria sem NENHUM humano concordar. A
     * identidade agora é (alvo, posição) — a prosa toda pode derivar que o dedupe pega.
     */
    @Test
    void settleReexecutadoComTextoReformulado_dedupePegaMesmoAssim() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                "```dirigir\n{\"alvo\": \"Bruno\", \"titulo\": \"Calendário de posts\","
                        + " \"descricao\": \"pautas de julho\", \"risco\": 2,"
                        + " \"racional\": \"pauta fria e o Bruno domina o assunto\"}\n```\n");
        // O reaper re-liquidou e o LLM reescreveu TUDO — mesma ordem, outras palavras.
        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                "```dirigir\n{\"alvo\": \"Bruno\", \"titulo\": \"Montar o calendário de posts\","
                        + " \"descricao\": \"planejamento editorial de julho\", \"risco\": 2,"
                        + " \"racional\": \"o assunto é do Bruno e a pauta esfriou\"}\n```\n");

        assertThat(approvalsDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId()).get(0).getTitle())
                .as("a 1ª execução real venceu — a re-execução dedupou, não sobrescreveu")
                .isEqualTo("Calendário de posts");
    }

    /**
     * A tensão que o fingerprint estrutural precisa respeitar (hardening item 1): o MESMO gestor,
     * no MESMO run, dirigindo 2 issues DIFERENTES pro MESMO alvo é legítimo (cap = 5/run). A
     * posição per-alvo distingue as duas — chave estreita demais colapsaria ordens genuínas.
     */
    @Test
    void duasOrdensDistintasProMesmoAlvoNoMesmoRun_naoColidem() {
        Cenario c = cenario();
        promoveAna(c);

        gate.handle(c.companyId(), c.ana().getId(), c.runId(),
                blocoDirigir("Bruno", "Revisar o PR X") + "\n"
                        + blocoDirigir("Bruno", "Atualizar a doc Y"));

        assertThat(approvalsDa(c.companyId())).hasSize(2);
        List<Issue> criadas = issuesDa(c.companyId());
        assertThat(criadas).hasSize(2);
        assertThat(criadas).extracting(Issue::getTitle)
                .containsExactlyInAnyOrder("Revisar o PR X", "Atualizar a doc Y");
        assertThat(criadas).allSatisfy(issue ->
                assertThat(issue.getAssigneeAgentId()).isEqualTo(c.bruno().getId()));
    }

    /**
     * O acompanhante do fingerprint estrutural: com posição-como-identidade, um ECO do LLM (o
     * mesmo bloco byte-idêntico 2× no MESMO reply) viraria 2 issues — o gate colapsa antes, com
     * aviso contado (anti-silêncio).
     */
    @Test
    void ordemRepetidaIdenticaNoMesmoReply_colapsaEmUmaComAviso() {
        Cenario c = cenario();
        promoveAna(c);
        String bloco = blocoDirigir("Bruno", "Calendário de posts");

        gate.handle(c.companyId(), c.ana().getId(), c.runId(), bloco + "\n" + bloco);

        assertThat(approvalsDa(c.companyId())).hasSize(1);
        assertThat(issuesDa(c.companyId())).hasSize(1);
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runId()))
                .anySatisfy(event -> assertThat(event.getDetail()).contains("repetida IDÊNTICA"));
    }

    private static ApprovalService.OrdemDirigida ordemDireta(Agent alvo, UUID bracoTeamId) {
        return new ApprovalService.OrdemDirigida(alvo.getId(), alvo.getTeamId(), bracoTeamId,
                "Golpe por fora do gate", null, 2, "um chamador que pulou o DirigirGate",
                "{\"targetAgentId\": \"" + alvo.getId() + "\", \"titulo\": \"Golpe por fora do gate\"}",
                "dirigir a " + alvo.getName() + ": Golpe por fora do gate", 1);
    }

    /**
     * O TESTE do hardening item 2 (defesa em profundidade): um "segundo chamador" hipotético que
     * chama {@code dirigirIssueSemGate} DIRETO, pulando o guard do DirigirGate. O próprio
     * ApprovalService recusa — por construção, não por convenção. Aqui: gestora em ESTÁGIO.
     */
    @Test
    void chamadorDiretoComGestoraEmEstagio_oProprioServiceRecusa() {
        Cenario c = cenario(); // Ana é gestora titular, mas NÃO promovida

        assertThatThrownBy(() -> approvalService.dirigirIssueSemGate(c.companyId(),
                c.ana().getId(), c.runId(), ordemDireta(c.bruno(), c.marketing().getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESTÁGIO");
        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
    }

    /** Item 2, segunda cerca: quem nem gestor do braço é não passa — mesmo promovido o braço. */
    @Test
    void chamadorDiretoPorQuemNaoEGestorDoBraco_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);

        assertThatThrownBy(() -> approvalService.dirigirIssueSemGate(c.companyId(),
                c.bruno().getId(), c.runId(), ordemDireta(c.bruno(), c.marketing().getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não é o gestor titular");
        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
    }

    /** Item 2, terceira cerca: alvo fora da subárvore do braço — a régua D-ORG-3 vale AQUI também. */
    @Test
    void chamadorDiretoComAlvoForaDaSubarvore_oProprioServiceRecusa() {
        Cenario c = cenario();
        promoveAna(c);

        assertThatThrownBy(() -> approvalService.dirigirIssueSemGate(c.companyId(),
                c.ana().getId(), c.runId(), ordemDireta(c.caio(), c.marketing().getId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FORA da subárvore");
        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
    }

    @Test
    void alvoAmbiguo_doisAgentesComOMesmoNome_descartaComAviso() {
        Cenario c = cenario();
        promoveAna(c);
        Agent xara = agents.save(new Agent(UUID.randomUUID(), c.companyId(), "Bruno", "designer", null));
        teamService.assignAgent(c.companyId(), xara.getId(), c.social().getId());

        gate.handle(c.companyId(), c.ana().getId(), c.runId(), blocoDirigir("Bruno", "Pauta"));

        assertThat(approvalsDa(c.companyId())).isEmpty();
        assertThat(issuesDa(c.companyId())).isEmpty();
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runId()))
                .anySatisfy(event -> assertThat(event.getDetail()).contains("AMBÍGUO"));
    }

    @Test
    void capDeOrdensPorRun_aSextaEstouraComAviso() {
        Cenario c = cenario();
        promoveAna(c);
        StringBuilder reply = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            reply.append(blocoDirigir("Bruno", "Ordem " + i)).append("\n");
        }

        gate.handle(c.companyId(), c.ana().getId(), c.runId(), reply.toString());

        assertThat(approvalsDa(c.companyId())).hasSize(5);
        assertThat(issuesDa(c.companyId())).hasSize(5);
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(c.runId()))
                .anySatisfy(event -> assertThat(event.getDetail()).contains("além do teto"));
    }
}
