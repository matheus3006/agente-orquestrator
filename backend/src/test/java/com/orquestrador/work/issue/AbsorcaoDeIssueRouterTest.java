package com.orquestrador.work.issue;

import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.conversation.persona.PersonaHandoffScanner;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.governance.approval.Approval;
import com.orquestrador.governance.approval.ApprovalActionType;
import com.orquestrador.governance.approval.ApprovalDecision;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O NÚCLEO da absorção de issues do board (ADR-0037): o dono cria uma issue órfã no board; o
 * Planejador tria contra a equipe e emite {@code ```absorcao```} apontando o TIME; o backend
 * resolve time→arquiteto DETERMINISTICAMENTE, seta o assignee (a issue nasce na mesa do
 * arquiteto), confirma na conversa ANCORADA na issue (issue = conversa direcionada, V27) e grava
 * Decision origem AGENTE — irmão INVERSO do caso B (aqui board→conversa; lá conversa→board).
 */
@SpringBootTest
@ActiveProfiles("test")
class AbsorcaoDeIssueRouterTest {

    @Autowired
    private AbsorcaoDeIssueRouter router;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private CompanyService companies;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationRepository conversationRepo;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private ActivityLogRepository activityLog;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private com.orquestrador.execution.HeartbeatDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    /** A fila do dispatcher é GLOBAL no H2 compartilhado: drenar antes/depois isola as ondas. */
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void drenaFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
    }

    /** M1 (V27): issue nasce SEM conversa (gate/corretiva/legado); ancorada aponta a sua 1:1. */
    @Test
    void issueNasceSemConversaEAncoradaApontaASua() {
        UUID companyId = novaCompany();
        Issue issue = issues.create(companyId, "Landing page nova", "hero + formulário");
        assertThat(issue.getConversationId()).as("issue não-absorvida fica sem conversa").isNull();

        Conversation conversa = conversations.open(companyId, "#1 — Landing page nova");
        issue.ancorarConversa(conversa.getId());
        issueRepo.save(issue);

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getConversationId())
                .isEqualTo(conversa.getId());
    }

    /** M5: o UPDATE guardado atribui SÓ a órfã OPEN — dono já setado ou company errada = 0 linhas. */
    @Test
    @org.springframework.transaction.annotation.Transactional
    void tryAbsorverAtribuiSoAOrfaOpenDaCompany() {
        UUID companyId = novaCompany();
        UUID arquitetoId = UUID.randomUUID();
        Issue orfa = issues.create(companyId, "Órfã", null);

        assertThat(issueRepo.tryAbsorver(orfa.getId(), companyId, arquitetoId)).isEqualTo(1);
        assertThat(issueRepo.findById(orfa.getId()).orElseThrow().getAssigneeAgentId())
                .isEqualTo(arquitetoId);

        // Já tem dona (o próprio absorver acima): segunda tentativa casa 0 linhas — nunca re-atribui.
        assertThat(issueRepo.tryAbsorver(orfa.getId(), companyId, UUID.randomUUID()))
                .as("issue com dono nunca é re-atribuída (D9.3)").isZero();
        assertThat(issueRepo.findById(orfa.getId()).orElseThrow().getAssigneeAgentId())
                .isEqualTo(arquitetoId);

        // Company errada não vaza: a issue existe, mas o guard company_id barra.
        Issue deOutra = issues.create(companyId, "Outra órfã", null);
        assertThat(issueRepo.tryAbsorver(deOutra.getId(), novaCompany(), arquitetoId)).isZero();
    }

    /** M2/D1+D4: issue do dono nasce COM conversa "#N — <título>" ancorada, gatilho e wakeup. */
    @Test
    void issueDoDonoNasceComConversaTurnoSentinelaEPlanejadorAcordado() {
        UUID companyId = novaCompany();
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador");

        Issue issue = router.aoNascerDoDono(companyId, "Landing page nova", "hero + formulário", null);

        // A conversa direcionada da issue: assunto determinístico, ancoragem 1:1 (V27).
        Issue salva = issueRepo.findById(issue.getId()).orElseThrow();
        assertThat(salva.getConversationId()).isNotNull();
        Conversation conversa = conversationRepo
                .findByIdAndCompanyId(salva.getConversationId(), companyId).orElseThrow();
        assertThat(conversa.getSubject()).isEqualTo("#1 — Landing page nova");

        // O turno-gatilho: PENDING do Planejador, com #N + título + descrição VERBATIM…
        List<ConversationTurn> daConversa = turns
                .findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversa.getId(), companyId);
        assertThat(daConversa).hasSize(1);
        ConversationTurn gatilho = daConversa.get(0);
        assertThat(gatilho.getAgentId()).isEqualTo(planejadorId);
        assertThat(gatilho.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(gatilho.getOwnerMessage())
                .contains("#1").contains("Landing page nova").contains("hero + formulário");
        // …e SENTINELA: invisível na thread do dono (filtro da ADR-0030).
        assertThat(conversations.thread(companyId, conversa.getId()))
                .as("o gatilho é interno — o dono não o vê").isEmpty();

        // O Planejador foi ACORDADO (espelho do postMessage): há run dele na company.
        assertThat(runs.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .extracting(HeartbeatRun::getAgentId).contains(planejadorId);
    }

    /** D9.6: company sem Planejador = ESTADO ILEGAL; skip fail-soft (letra morta declarada). */
    @Test
    void companySemPlanejadorCriaOrfaSemConversaNemTurno() {
        UUID companyId = novaCompany();

        Issue issue = router.aoNascerDoDono(companyId, "Sem triagem", null, null);

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getConversationId()).isNull();
        assertThat(conversations.list(companyId)).isEmpty();
    }

    /** M2/D5+D8: o feliz — bloco aponta o TIME, sistema resolve arquiteto, atribui e registra. */
    @Test
    void rotearFelizAtribuiAoArquitetoComDecisionEventoEConfirmacao() {
        Cenario cenario = cenarioComTime("Dev", "Landing");
        Issue issue = router.aoNascerDoDono(cenario.companyId, "Landing page nova",
                "hero + formulário", null);
        ConversationTurn turnoDoPlanejador = turnoAnsweredDoPlanejador(cenario, issue);

        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Dev", "Landing", "é trabalho de frontend"))).isTrue();

        // A issue segue OPEN na mesa da arquiteta — o tryCheckout só aceita ELA (guard provado).
        Issue absorvida = issueRepo.findById(issue.getId()).orElseThrow();
        assertThat(absorvida.getAssigneeAgentId()).isEqualTo(cenario.arquitetoId);
        assertThat(absorvida.getStatus()).isEqualTo(IssueStatus.OPEN);

        // Decision origem AGENTE (D8): esqueleto determinístico; evidencia = o racional do bloco.
        List<Decision> doLog = decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId);
        assertThat(doLog).hasSize(1);
        Decision decision = doLog.get(0);
        assertThat(decision.getOrigem()).isEqualTo(DecisionOrigem.AGENTE);
        assertThat(decision.getTipo()).isEqualTo(DecisionTipo.OPERACIONAL);
        assertThat(decision.getOQueMudou())
                .contains("#" + issue.getSeq()).contains("Dev / Landing").contains("Ana Arquiteta");
        assertThat(decision.getEvidencia()).isEqualTo("é trabalho de frontend");
        assertThat(decision.getIssueId()).isEqualTo(issue.getId());

        // Evento ISSUE_ABSORVIDA no activity_log (só IDs, ADR-0009).
        assertThat(activityLog.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId))
                .extracting(com.orquestrador.activitylog.ActivityLog::getAction)
                .contains("ISSUE_ABSORVIDA");

        // Confirmação VISÍVEL na thread da issue: ANSWERED, zero run, com time+arquiteta+racional.
        ConversationTurn confirmacao = ultimoTurno(cenario.companyId, issue.getConversationId());
        assertThat(confirmacao.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(confirmacao.getRunId()).isNull();
        assertThat(confirmacao.getCeoReply()).contains("#" + issue.getSeq())
                .contains("Dev / Landing").contains("Ana Arquiteta")
                .contains("é trabalho de frontend");
        assertThat(conversations.thread(cenario.companyId, issue.getConversationId()))
                .anyMatch(turno -> turno.getId().equals(confirmacao.getId()));
    }

    /** D9.3: issue que JÁ tem dono → aviso "nada mudei", assignee INTACTO, zero Decision. */
    @Test
    void issueComDonoAvisaNadaMudeiSemReAtribuir() {
        Cenario cenario = cenarioComTime("Dev", "Landing");
        Issue issue = router.aoNascerDoDono(cenario.companyId, "Já tem dona", null, null);
        UUID donaOriginal = cenario.arquitetoId;
        jdbc.update("update issue set assignee_agent_id = ? where id = ?",
                donaOriginal, issue.getId());
        ConversationTurn turnoDoPlanejador = turnoAnsweredDoPlanejador(cenario, issue);

        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Dev", "Landing", "atribuir de novo"))).isTrue();

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getAssigneeAgentId())
                .isEqualTo(donaOriginal);
        assertThat(ultimoTurno(cenario.companyId, issue.getConversationId()).getCeoReply())
                .contains("já tem dona").contains("Ana Arquiteta").contains("nada mudei");
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).isEmpty();
    }

    /** D6: NENHUM time cobre → recomendação de cargo VISÍVEL, nada atribuído, zero Decision. */
    @Test
    void timeInexistenteViraRecomendacaoDeCargoSemAtribuir() {
        Cenario cenario = cenarioComTime("Dev", "Landing");
        Issue issue = router.aoNascerDoDono(cenario.companyId, "Campanha de tráfego pago", null, null);
        ConversationTurn turnoDoPlanejador = turnoAnsweredDoPlanejador(cenario, issue);

        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Marketing", "Tráfego",
                "um arquiteto de aquisição + um executor de mídia paga"))).isTrue();

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getAssigneeAgentId()).isNull();
        String aviso = ultimoTurno(cenario.companyId, issue.getConversationId()).getCeoReply();
        assertThat(aviso).contains("Marketing / Tráfego").contains("não existe")
                .contains("um arquiteto de aquisição + um executor de mídia paga");
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).isEmpty();
    }

    /** D9.5 (legado): time citado SEM arquiteto → para-e-avisa acionável, nada atribuído. */
    @Test
    void timeSemArquitetoAvisaENadaAtribui() {
        Cenario cenario = cenarioComTime("Dev", "Landing");
        Team time = teams.findByIdAndCompanyId(cenario.teamId, cenario.companyId).orElseThrow();
        time.designarArquiteto(null);
        teams.save(time);
        Issue issue = router.aoNascerDoDono(cenario.companyId, "Landing page nova", null, null);
        ConversationTurn turnoDoPlanejador = turnoAnsweredDoPlanejador(cenario, issue);

        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Dev", "Landing", "frontend"))).isTrue();

        assertThat(issueRepo.findById(issue.getId()).orElseThrow().getAssigneeAgentId()).isNull();
        assertThat(ultimoTurno(cenario.companyId, issue.getConversationId()).getCeoReply())
                .contains("sem arquiteto").contains("Dev / Landing");
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).isEmpty();
    }

    /**
     * D7/M8 (retoma automática): o APPROVE do CREATE_TEAM ancorado na conversa da issue posta o
     * bastão ao Planejador — "o time existe; retome a #N" — sem o dono digitar nada além do
     * approve (que É a autorização; freio humano por construção, D9.8).
     */
    @Test
    void approveDoCreateTeamRetomaATriagemDaIssueAncorada() {
        UUID companyId = novaCompany();
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador");
        UUID executorId = persona(companyId, "executor", "CEO Executor");
        Issue issue = router.aoNascerDoDono(companyId, "Campanha de tráfego pago", null, null);

        Approval proposta = approvals.propose(companyId, executorId, issue.getConversationId(),
                null, new com.orquestrador.governance.approval.ProposalScanner.Proposal(
                        ApprovalActionType.CREATE_TEAM,
                        "{\"dominio\":\"Marketing\",\"foco\":\"Tráfego\",\"arquiteto\":"
                                + "{\"name\":\"Aqua\",\"role\":\"arquiteto\"}}",
                        "Criar time Marketing/Tráfego", null));
        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "não há time de aquisição", null,
                com.orquestrador.common.security.ActorType.BOARD, UUID.randomUUID());

        ConversationTurn retoma = ultimoTurno(companyId, issue.getConversationId());
        assertThat(retoma.getAgentId()).isEqualTo(planejadorId);
        assertThat(retoma.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(retoma.getOwnerMessage())
                .contains("#" + issue.getSeq()).contains("Marketing / Tráfego");
        assertThat(conversations.thread(companyId, issue.getConversationId()))
                .as("a retoma é interna — invisível pro dono")
                .noneMatch(turno -> turno.getId().equals(retoma.getId()));
    }

    /** Fail-soft (D9.5) + idempotência (D9.1): #N inexistente descarta; settle reexecutado no-op. */
    @Test
    void issueInexistenteDescartaESettleReexecutadoNaoDuplica() {
        Cenario cenario = cenarioComTime("Dev", "Landing");
        Issue issue = router.aoNascerDoDono(cenario.companyId, "Landing page nova", null, null);
        ConversationTurn turnoDoPlanejador = turnoAnsweredDoPlanejador(cenario, issue);

        // #99 não existe: descarte fail-soft — nem aviso nasce.
        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                99, "Dev", "Landing", "r"))).isFalse();

        // O feliz roda…
        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Dev", "Landing", "frontend"))).isTrue();
        // …e o settle reexecutado do MESMO gatilho é no-op: nada duplica.
        assertThat(router.rotear(turnoDoPlanejador, new PersonaHandoffScanner.Absorcao(
                issue.getSeq(), "Dev", "Landing", "frontend")))
                .as("reexecução = no-op fail-soft").isFalse();

        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).hasSize(1);
        long confirmacoes = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                        issue.getConversationId(), cenario.companyId).stream()
                .filter(turno -> turno.getOwnerMessage().contains("[gatilho")).count();
        assertThat(confirmacoes).isEqualTo(1);
    }

    // ---- cenário ----

    private UUID novaCompany() {
        return companies.create("C", "absorcao-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID persona(UUID companyId, String persona, String nome) {
        Agent agente = new Agent(UUID.randomUUID(), companyId, nome, "ceo", null);
        agente.definePersona(persona);
        return agents.save(agente).getId();
    }

    private record Cenario(UUID companyId, UUID planejadorId, UUID arquitetoId, UUID teamId) {
    }

    /** Company com Planejador e um time (dominio/foco) com a arquiteta Ana designada. */
    private Cenario cenarioComTime(String dominio, String foco) {
        UUID companyId = novaCompany();
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador");
        Team time = teams.save(new Team(UUID.randomUUID(), companyId, dominio, foco));
        Agent ana = new Agent(UUID.randomUUID(), companyId, "Ana Arquiteta", "arquiteto", null);
        ana.assignTeam(time.getId());
        UUID arquitetoId = agents.save(ana).getId();
        time.designarArquiteto(arquitetoId);
        teams.save(time);
        return new Cenario(companyId, planejadorId, arquitetoId, time.getId());
    }

    /** O turno do Planejador que emitiu o bloco — o gatilho do rotear, já ANSWERED no settle. */
    private ConversationTurn turnoAnsweredDoPlanejador(Cenario cenario, Issue issue) {
        ConversationTurn turno = new ConversationTurn(UUID.randomUUID(), issue.getConversationId(),
                cenario.companyId, cenario.planejadorId, "[continuação — gatilho de teste]");
        turno.answer("Triei a #" + issue.getSeq() + ".");
        return turns.save(turno);
    }

    private ConversationTurn ultimoTurno(UUID companyId, UUID conversationId) {
        List<ConversationTurn> daConversa = turns
                .findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversationId, companyId);
        return daConversa.get(daConversa.size() - 1);
    }
}
