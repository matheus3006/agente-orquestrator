package com.orquestrador.governance.approval;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.governance.approval.ApprovalService.DecideOutcome;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.CompanyMemory;
import com.orquestrador.memory.CompanyMemoryRepository;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.memory.MemoryKind;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O coração de T.3 (ADR-0018/0019/0020): proposta na SAÍDA do CEO → Approval PENDING; o board
 * decide com racional-arrancado-por-grill; APPROVE dispara a ação real (ator=aprovador); todo
 * commit fabrica uma Decisão; direção consolida a carta (projeção determinística).
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalGateTest {

    @Autowired
    private ApprovalService approvals;
    @Autowired
    private ApprovalRepository approvalRepo;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueRepository issues;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private CompanyMemoryRepository memories;
    @Autowired
    private ActivityLogRepository activities;

    /** O wakeup aprovado enfileira sem despachar; não deixar QUEUED pros vizinhos de contexto. */
    @AfterEach
    void drenaFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID contrataCeu(UUID companyId) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", null, "LOCAL");
        return agents.save(ceo).getId();
    }

    private ProposalScanner.Proposal hireNix() {
        return new ProposalScanner.Proposal(ApprovalActionType.HIRE_AGENT,
                "{\"name\":\"Nix\",\"role\":\"executor\"}", "Contratar Nix executor", null);
    }

    @Test
    void respostaDoCeoNoRunViraApprovalPendingAncoradaNaConversa() {
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        // O CEO (arquétipo process) emite um bloco de proposta na SAÍDA — o caminho REAL do gate.
        ceo.configureRuntime("process", "printf '%s\\n' '```proposta' "
                + "'{\"acao\": \"HIRE_AGENT\", \"alvo\": {\"name\": \"Nix\", \"role\": \"executor\"}, "
                + "\"resumo\": \"Contratar Nix\"}' '```'", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();

        Conversation geral = conversations.open(companyId, "Geral");
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, geral.getId(), "precisamos de bracos", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<Approval> pending = approvals.queue(companyId, ApprovalStatus.PENDING);
        assertThat(pending).hasSize(1);
        Approval proposta = pending.get(0);
        assertThat(proposta.getActionType()).isEqualTo(ApprovalActionType.HIRE_AGENT);
        assertThat(proposta.getRequestedByAgentId()).isEqualTo(ceoId);
        assertThat(proposta.getConversationId()).isEqualTo(geral.getId()); // âncora da thread
        assertThat(proposta.getRunId()).isEqualTo(receipt.runId());
        assertThat(proposta.getRacionalTipo()).isEqualTo(RacionalTipo.LOGICO); // derivado (D9)

        // CEO_PROPOSED com ator=AGENTE (a proposta é dele; a decisão será do dono).
        assertThat(activities.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo(ActivityAction.CEO_PROPOSED.name());
                    assertThat(entry.getActorType()).isEqualTo(ActorType.AGENT.name());
                    assertThat(entry.getActorId()).isEqualTo(ceoId);
                });
    }

    @Test
    void reEmissaoDaMesmaPropostaNaoEmpilhaPendente() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);

        Approval primeira = approvals.propose(companyId, ceoId, null, null, hireNix());
        Approval segunda = approvals.propose(companyId, ceoId, null, null, hireNix());

        assertThat(segunda.getId()).isEqualTo(primeira.getId()); // dedupe: a MESMA PENDING
        assertThat(approvals.queue(companyId, ApprovalStatus.PENDING)).hasSize(1);
    }

    @Test
    void grillArrancaORacionalEAproveDisparaAcaoRealComAtorAprovador() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        UUID boardUser = UUID.randomUUID();
        Approval proposta = approvals.propose(companyId, ceoId, null, null, hireNix());

        // 1ª tentativa sem racional → grelhada (o commit NÃO acontece; a rodada fica contada).
        DecideOutcome vazio = approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                " ", null, ActorType.BOARD, boardUser);
        assertThat(vazio).isInstanceOf(DecideOutcome.Grilled.class);
        // 2ª tentativa com "porque quis" → rejeitada (D7, anti-confabulação).
        DecideOutcome confab = approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "porque quis", null, ActorType.BOARD, boardUser);
        assertThat(confab).isInstanceOf(DecideOutcome.Grilled.class);
        assertThat(((DecideOutcome.Grilled) confab).rounds()).isEqualTo(2);
        assertThat(approvalRepo.findById(proposta.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);

        // 3ª com razão substantiva → COMMITA: hire real disparado com ator=aprovador.
        DecideOutcome commit = approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "runway curto: precisamos de executor pro MVP ja", null, ActorType.BOARD, boardUser);
        assertThat(commit).isInstanceOf(DecideOutcome.Committed.class);

        Approval decidida = approvalRepo.findById(proposta.getId()).orElseThrow();
        assertThat(decidida.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decidida.isRacionalArrancado()).isTrue(); // veio de grill (2 rodadas)
        assertThat(decidida.getGrillRounds()).isEqualTo(2);

        assertThat(agents.findByCompanyId(companyId))
                .anySatisfy(agent -> {
                    assertThat(agent.getName()).isEqualTo("Nix");
                    assertThat(agent.getRole()).isEqualTo("executor");
                });

        // A Decisão fabricada no gate (D5), ancorada na approval, origem = proposta do CEO.
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId))
                .anySatisfy(decision -> {
                    assertThat(decision.getApprovalId()).isEqualTo(proposta.getId());
                    assertThat(decision.getOrigem()).isEqualTo(DecisionOrigem.PROPOSTA_CEO);
                    assertThat(decision.isRacionalArrancado()).isTrue();
                });

        // UM evento de ação delegada: ator=aprovador, detail carrega o requestedByAgentId (ADR-0009).
        assertThat(activities.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo(ActivityAction.APPROVAL_APPROVED.name());
                    assertThat(entry.getActorType()).isEqualTo(ActorType.BOARD.name());
                    assertThat(entry.getActorId()).isEqualTo(boardUser);
                });
    }

    @Test
    void rejectRegistraDecisaoDoDonoSemDispararAcao() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null, hireNix());

        DecideOutcome outcome = approvals.decide(companyId, proposta.getId(), ApprovalDecision.REJECT,
                "cedo demais: valida o nicho antes de contratar", "valide o nicho primeiro",
                ActorType.BOARD, UUID.randomUUID());

        assertThat(outcome).isInstanceOf(DecideOutcome.Committed.class);
        assertThat(agents.findByCompanyId(companyId)).hasSize(1); // só o CEO — nada contratado
        Approval decidida = approvalRepo.findById(proposta.getId()).orElseThrow();
        assertThat(decidida.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(decidida.getDecisionReason()).isEqualTo("valide o nicho primeiro");
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId))
                .anySatisfy(decision -> {
                    assertThat(decision.getOrigem()).isEqualTo(DecisionOrigem.DONO_NO_GATE);
                    assertThat(decision.getApprovalId()).isEqualTo(proposta.getId());
                });
    }

    @Test
    void direcaoAprovadaConsolidaACartaDeterministicamente() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        ProposalScanner.Proposal direcao = new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                "{\"title\":\"Focar no nicho dental\"}", "Focar no nicho dental", "direcao-roadmap");
        Approval proposta = approvals.propose(companyId, ceoId, null, null, direcao);

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "o nicho dental tem dor aguda e ticket alto", null, ActorType.BOARD, UUID.randomUUID());

        // A ação real: Issue criada. A Decisão é de DIREÇÃO → a carta consolida (v1).
        // O caminho do approve passa pelo MESMO chokepoint do POST do board — a Issue nasce com #N (ADR-0033).
        assertThat(issues.findByCompanyId(companyId))
                .anySatisfy(issue -> {
                    assertThat(issue.getTitle()).isEqualTo("Focar no nicho dental");
                    assertThat(issue.getSeq()).isEqualTo(1);
                });
        Decision decisao = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);
        assertThat(decisao.getTipo()).isEqualTo(DecisionTipo.DIRECAO);

        CompanyMemory carta = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA).orElseThrow();
        assertThat(carta.getVersion()).isEqualTo(1);
        assertThat(carta.getBody()).contains("Focar no nicho dental");
        assertThat(carta.getBody()).contains("o nicho dental tem dor aguda e ticket alto");
    }

    @Test
    void areaCriativaCommitaNaHoraComPreferencia() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        ProposalScanner.Proposal criativa = new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                "{\"title\":\"Refazer a home com tom leve\"}", "Refazer a home", "design-visual");
        Approval proposta = approvals.propose(companyId, ceoId, null, null, criativa);
        assertThat(proposta.getRacionalTipo()).isEqualTo(RacionalTipo.INTUICAO); // D9

        // D8: preferência legítima commita NA HORA — sem exigência de premissa.
        DecideOutcome outcome = approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "me agradou o tom leve", null, ActorType.BOARD, UUID.randomUUID());
        assertThat(outcome).isInstanceOf(DecideOutcome.Committed.class);

        // O probe maiêutico (D10) fica como EVIDÊNCIA da Decisão, não como bloqueio.
        Decision decisao = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);
        assertThat(decisao.getRacionalTipo()).isEqualTo(RacionalTipo.INTUICAO);
        assertThat(decisao.getEvidencia()).contains("maieutico");
    }

    @Test
    void decidirApprovalJaDecididaE409() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null, hireNix());
        approvals.decide(companyId, proposta.getId(), ApprovalDecision.REJECT,
                "sem espaco no orcamento deste ciclo", null, ActorType.BOARD, UUID.randomUUID());

        assertThatThrownBy(() -> approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "mudou nada", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void wakeupAprovadoEnfileiraRunDoAgenteAlvo() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Agent echo = new Agent(UUID.randomUUID(), companyId, "Echo", "executor", null);
        echo.configureRuntime("process", null, "LOCAL");
        UUID echoId = agents.save(echo).getId();

        ProposalScanner.Proposal wakeup = new ProposalScanner.Proposal(ApprovalActionType.WAKEUP_AGENT,
                "{\"agentId\":\"" + echoId + "\"}", "Acordar Echo", null);
        Approval proposta = approvals.propose(companyId, ceoId, null, null, wakeup);
        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "a fila do Echo esta vazia e ha trabalho pronto", null, ActorType.BOARD, UUID.randomUUID());

        assertThat(runs.findByAgentAndStatuses(echoId,
                List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING))).hasSize(1);
    }
}
