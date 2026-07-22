package com.orquestrador.work.issue;

import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
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
 * O NÚCLEO do caso B (ADR-0034, M4): o dono apontou que a task ENTREGUE #N ficou ruim; o backend
 * resolve a corrente #N → assignee → time → arquiteto DETERMINISTICAMENTE (a corrente está no
 * banco — zero LLM, zero gate: o pedido do dono É a autorização humana), cria a task corretiva
 * (descrição = as palavras do dono copiadas BYTE A BYTE do turno), grava a 1ª Decision de origem
 * AGENTE do produto e confirma na conversa — tudo num settle, idempotente sob reexecução.
 * Corrente quebrada / #N não-DONE = para-e-avisa acionável, NADA criado.
 */
@SpringBootTest
@ActiveProfiles("test")
class CorrecaoDeEntregaRouterTest {

    private static final String RECLAMACAO = "a task #1 ficou ruim — o formulário não valida e-mail";

    @Autowired
    private CorrecaoDeEntregaRouter router;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void felizCriaCorretivaComVerbatimDecisionEConfirmacao() {
        Cenario cenario = cenarioCompleto();
        Issue original = entregue(cenario, "Landing page");
        ConversationTurn gatilho = turnoDoDono(cenario, RECLAMACAO);

        assertThat(router.rotear(gatilho, original.getSeq())).isTrue();

        // A corretiva: nasce ligada, com o verbatim BYTE A BYTE e na mesa do arquiteto; OPEN.
        Issue corretiva = issueRepo.findByCompanyIdAndSeq(cenario.companyId, 2).orElseThrow();
        assertThat(corretiva.getTitle()).isEqualTo("Correção da #1 — Landing page");
        assertThat(corretiva.getDescription()).isEqualTo(RECLAMACAO);
        assertThat(corretiva.getCorrigeIssueId()).isEqualTo(original.getId());
        assertThat(corretiva.getAssigneeAgentId()).isEqualTo(cenario.arquitetoId);
        assertThat(corretiva.getStatus()).isEqualTo(IssueStatus.OPEN);

        // A #N segue DONE intacta (REOPEN é para BLOCKED — fora do caso B).
        assertThat(issueRepo.findById(original.getId()).orElseThrow().getStatus())
                .isEqualTo(IssueStatus.DONE);

        // A 1ª Decision de origem AGENTE do produto: determinística, evidencia = verbatim.
        List<Decision> doLog = decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId);
        assertThat(doLog).hasSize(1);
        Decision decision = doLog.get(0);
        assertThat(decision.getOrigem()).isEqualTo(DecisionOrigem.AGENTE);
        assertThat(decision.getTipo()).isEqualTo(DecisionTipo.OPERACIONAL);
        assertThat(decision.getEvidencia()).isEqualTo(RECLAMACAO);
        assertThat(decision.getIssueId()).isEqualTo(corretiva.getId());
        assertThat(decision.getRacional())
                .contains("#1").contains("Ana Arquiteta").contains("#2");

        // A confirmação: turno ANSWERED do Questionador, SEM run novo, VISÍVEL na thread do dono.
        List<ConversationTurn> daConversa = turns
                .findByConversationIdAndCompanyIdOrderByCreatedAtAsc(cenario.conversationId,
                        cenario.companyId);
        ConversationTurn confirmacao = daConversa.get(daConversa.size() - 1);
        assertThat(confirmacao.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(confirmacao.getRunId()).isNull();
        assertThat(confirmacao.getAgentId()).isEqualTo(cenario.questionadorId);
        assertThat(confirmacao.getCeoReply())
                .contains("#1").contains("#2").contains("Ana Arquiteta");
        assertThat(conversations.thread(cenario.companyId, cenario.conversationId))
                .anyMatch(turno -> turno.getId().equals(confirmacao.getId()));
    }

    /** D2: elo vazio na corrente = para-e-avisa ACIONÁVEL; NADA é criado (nem task nem Decision). */
    @Test
    void correnteQuebradaAvisaENadaCria() {
        // elo 1: #N sem assignee (nunca passou por checkout)
        Cenario semAssignee = cenarioCompleto();
        Issue nuncaExecutada = issues.create(semAssignee.companyId, "Órfã", null);
        forcaDone(semAssignee.companyId, nuncaExecutada);
        assertThat(router.rotear(turnoDoDono(semAssignee, "a #1 ficou ruim"), 1)).isTrue();
        assertAvisoSemCriacao(semAssignee, "sem responsável registrado");

        // elo 2: assignee fora de qualquer time
        Cenario semTime = cenarioCompleto();
        UUID avulso = agenteNoTime(semTime.companyId, "Avulso", null);
        Issue entregueAvulso = issues.create(semTime.companyId, "Do avulso", null);
        UUID runAvulso = runReal(semTime.companyId, avulso, entregueAvulso.getId());
        issues.checkout(semTime.companyId, entregueAvulso.getId(), avulso, runAvulso);
        issues.complete(semTime.companyId, entregueAvulso.getId(), runAvulso, avulso);
        assertThat(router.rotear(turnoDoDono(semTime, "a #1 ficou ruim"), 1)).isTrue();
        assertAvisoSemCriacao(semTime, "fora de qualquer time");

        // elo 3: time sem arquiteto (a trava-espelho do assertDispatchAllowed)
        Cenario semArquiteto = cenarioCompleto();
        Issue entregueNormal = entregue(semArquiteto, "Landing page");
        Team time = teams.findByIdAndCompanyId(semArquiteto.teamId, semArquiteto.companyId).orElseThrow();
        time.designarArquiteto(null);
        teams.save(time);
        assertThat(router.rotear(turnoDoDono(semArquiteto, "a #" + entregueNormal.getSeq()
                + " ficou ruim"), entregueNormal.getSeq())).isTrue();
        assertAvisoSemCriacao(semArquiteto, "está sem arquiteto");
    }

    /** D7 (guarda de definição): #N que não está DONE → aviso; o caso B é para task ENTREGUE. */
    @Test
    void issueNaoEntregueAvisaENadaCria() {
        Cenario cenario = cenarioCompleto();
        issues.create(cenario.companyId, "Ainda aberta", null); // OPEN

        assertThat(router.rotear(turnoDoDono(cenario, "a #1 ficou ruim"), 1)).isTrue();

        assertAvisoSemCriacao(cenario, "ainda não está entregue");
    }

    /** Fail-soft (DoD 8): #N inexistente = descarte com log — nem aviso nasce, a thread segue. */
    @Test
    void issueInexistenteEDescartadaSemNada() {
        Cenario cenario = cenarioCompleto();
        ConversationTurn gatilho = turnoDoDono(cenario, "a #99 ficou ruim");

        assertThat(router.rotear(gatilho, 99)).isFalse();

        assertThat(issueRepo.findByCompanyId(cenario.companyId)).isEmpty();
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).isEmpty();
        assertThat(turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                cenario.conversationId, cenario.companyId))
                .as("nenhum turno além do gatilho")
                .extracting(ConversationTurn::getId).containsExactly(gatilho.getId());
    }

    /** D7 (linhagem): caso B sobre a própria corretiva → sinal "2ª correção na linhagem da #N". */
    @Test
    void correcaoDeCorrecaoSinalizaALinhagemSemTravar() {
        Cenario cenario = cenarioCompleto();
        Issue original = entregue(cenario, "Landing page");
        router.rotear(turnoDoDono(cenario, "a task #1 ficou ruim — valida e-mail"), original.getSeq());
        Issue primeiraCorretiva = issueRepo.findByCompanyIdAndSeq(cenario.companyId, 2).orElseThrow();
        // a 1ª corretiva foi ENTREGUE pelo arquiteto… e ficou ruim de novo
        UUID runDoArquiteto = runReal(cenario.companyId, cenario.arquitetoId, primeiraCorretiva.getId());
        issues.checkout(cenario.companyId, primeiraCorretiva.getId(), cenario.arquitetoId, runDoArquiteto);
        issues.complete(cenario.companyId, primeiraCorretiva.getId(), runDoArquiteto, cenario.arquitetoId);

        assertThat(router.rotear(turnoDoDono(cenario, "a #2 continua ruim — agora o botão"), 2)).isTrue();

        Issue segundaCorretiva = issueRepo.findByCompanyIdAndSeq(cenario.companyId, 3).orElseThrow();
        assertThat(segundaCorretiva.getCorrigeIssueId()).isEqualTo(primeiraCorretiva.getId());
        String sinal = "2ª correção na linhagem da #1";
        assertThat(ultimoTurno(cenario).getCeoReply()).contains(sinal);
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId).get(0)
                .getRacional()).contains(sinal);
    }

    /** Idempotência (M4): o settle reexecutado pelo reaper NÃO duplica task/Decision/confirmação. */
    @Test
    void settleReexecutadoNaoDuplicaNada() {
        Cenario cenario = cenarioCompleto();
        Issue original = entregue(cenario, "Landing page");
        ConversationTurn gatilho = turnoDoDono(cenario, RECLAMACAO);

        assertThat(router.rotear(gatilho, original.getSeq())).isTrue();
        assertThat(router.rotear(gatilho, original.getSeq()))
                .as("reexecução = no-op fail-soft").isFalse();

        assertThat(issueRepo.findByCompanyId(cenario.companyId)).hasSize(2); // original + 1 corretiva
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).hasSize(1);
        long protocolos = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                        cenario.conversationId, cenario.companyId).stream()
                .filter(turno -> turno.getOwnerMessage().startsWith("Correção da #")).count();
        assertThat(protocolos).isEqualTo(1);
    }

    // ---- asserts compartilhados ----

    /** Aviso acionável postado (ANSWERED, sem run) e NADA criado além do que o cenário já tinha. */
    private void assertAvisoSemCriacao(Cenario cenario, String trechoDoAviso) {
        ConversationTurn aviso = ultimoTurno(cenario);
        assertThat(aviso.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(aviso.getRunId()).isNull();
        assertThat(aviso.getCeoReply()).contains(trechoDoAviso).contains("Nada foi criado");
        assertThat(issueRepo.findByCompanyId(cenario.companyId))
                .as("nenhuma corretiva nasce de corrente quebrada")
                .allMatch(issue -> issue.getCorrigeIssueId() == null);
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(cenario.companyId)).isEmpty();
    }

    private ConversationTurn ultimoTurno(Cenario cenario) {
        List<ConversationTurn> daConversa = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                cenario.conversationId, cenario.companyId);
        return daConversa.get(daConversa.size() - 1);
    }

    // ---- cenário ----

    private record Cenario(UUID companyId, UUID conversationId, UUID questionadorId,
                           UUID arquitetoId, UUID executorId, UUID teamId) {
    }

    /** Company com time completo (arquiteto designado), executor no time e conversa aberta. */
    private Cenario cenarioCompleto() {
        UUID companyId = companies.create("C", "casob-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Team time = teams.save(new Team(UUID.randomUUID(), companyId, "Dev", "Landing", null));
        UUID arquitetoId = agenteNoTime(companyId, "Ana Arquiteta", time.getId());
        UUID executorId = agenteNoTime(companyId, "Feynman", time.getId());
        time.designarArquiteto(arquitetoId);
        teams.save(time);
        UUID questionadorId = agenteNoTime(companyId, "CEO Questionador", null);
        Conversation conversa = conversations.open(companyId, "Negócio");
        return new Cenario(companyId, conversa.getId(), questionadorId, arquitetoId, executorId,
                time.getId());
    }

    private UUID agenteNoTime(UUID companyId, String nome, UUID teamId) {
        Agent agente = new Agent(UUID.randomUUID(), companyId, nome, "executor", null);
        agente.assignTeam(teamId);
        return agents.save(agente).getId();
    }

    /**
     * DONE à força, SEM assignee: inalcançável pelo funil normal (complete exige checkout, que
     * fixa o assignee) — o elo-1 quebrado é DEFENSIVO, e só um UPDATE cru fabrica o estado.
     */
    private void forcaDone(UUID companyId, Issue issue) {
        jdbc.update("update issue set status = 'DONE' where id = ? and company_id = ?",
                issue.getId(), companyId);
    }

    /** Uma Issue ENTREGUE pelo executor do cenário (checkout + complete = DONE de verdade). */
    private Issue entregue(Cenario cenario, String titulo) {
        Issue issue = issues.create(cenario.companyId, titulo, "entregável original");
        UUID runId = runReal(cenario.companyId, cenario.executorId, issue.getId());
        issues.checkout(cenario.companyId, issue.getId(), cenario.executorId, runId);
        issues.complete(cenario.companyId, issue.getId(), runId, cenario.executorId);
        return issue;
    }

    /** Um Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter). */
    private UUID runReal(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.RUNNING)).getId();
    }

    /** O turno-gatilho: a mensagem do dono que o Questionador acabou de responder (ANSWERED). */
    private ConversationTurn turnoDoDono(Cenario cenario, String palavrasDoDono) {
        ConversationTurn turno = new ConversationTurn(UUID.randomUUID(), cenario.conversationId,
                cenario.companyId, cenario.questionadorId, palavrasDoDono);
        turno.answer("Encaminho a correção.");
        return turns.save(turno);
    }
}
