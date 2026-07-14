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
import com.orquestrador.memory.DecisionGrau;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.DecisionTipo;
import com.orquestrador.memory.MemoryKind;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.roletemplate.RoleTemplate;
import com.orquestrador.org.roletemplate.RoleTemplateVersion;
import com.orquestrador.skills.CompanySkill;
import com.orquestrador.skills.CompanySkillRepository;
import com.orquestrador.skills.Skill;
import com.orquestrador.skills.SkillIndexer;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
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
    @Autowired
    private com.orquestrador.org.team.TeamRepository teams;
    @Autowired
    private com.orquestrador.org.roletemplate.RoleTemplateRepository roleTemplates;
    @Autowired
    private com.orquestrador.org.roletemplate.RoleTemplateVersionRepository roleTemplateVersions;
    @Autowired
    private IssueService issueService;
    @Autowired
    private SkillIndexer skillIndexer;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private CompanySkillRepository companySkills;
    @Autowired
    private com.orquestrador.skills.SkillRepository skillsCatalog;

    @TempDir
    Path skillsRoot;

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

    /** Um executor comum (não-CEO) — quem trabalha Issues e pede skill mid-run (F-AT1). */
    private UUID contrataExecutor(UUID companyId) {
        Agent executor = new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null);
        executor.configureRuntime("process", null, "LOCAL");
        return agents.save(executor).getId();
    }

    /** Skill só no catálogo GLOBAL (etapa A do indexer) — AINDA não instalada em nenhuma company. */
    private Skill skillGlobalDisponivel(String skillKey) throws java.io.IOException {
        Path dir = Files.createDirectories(skillsRoot.resolve(skillKey));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skillKey + "\ndescription: d\n---\n# corpo\n");
        skillIndexer.syncGlobalCatalog(skillsRoot);
        return skillsCatalog.findBySkillKey(skillKey).orElseThrow();
    }

    private ProposalScanner.Proposal pedidoSkill(String skillKey, UUID issueId) {
        return new ProposalScanner.Proposal(ApprovalActionType.SKILL_INSTALL_REQUEST,
                "{\"issueId\":\"" + issueId + "\",\"skillKey\":\"" + skillKey + "\"}",
                "Pedido de skill: " + skillKey, "skills");
    }

    /** Um time (dominio/foco) com arquiteto designado — o alvo da corrente time→arquiteto. */
    private UUID timeComArquiteto(UUID companyId, String dominio, String foco, String arquiteto) {
        com.orquestrador.org.team.Team time = teams.save(
                new com.orquestrador.org.team.Team(UUID.randomUUID(), companyId, dominio, foco));
        Agent ana = new Agent(UUID.randomUUID(), companyId, arquiteto, "arquiteto", null);
        ana.assignTeam(time.getId());
        UUID arquitetoId = agents.save(ana).getId();
        time.designarArquiteto(arquitetoId);
        teams.save(time);
        return arquitetoId;
    }

    /**
     * ADR-0037/D7: a 4ª ação do gate — CREATE_TEAM cria o time E contrata o arquiteto na MESMA
     * tx: o time do gate nasce com arquiteto POR CONSTRUÇÃO (executores vêm depois, como
     * HIRE_AGENT individuais — aprovação incremental da ADR-0017 intocada).
     */
    @Test
    void createTeamAprovadoCriaTimeComArquitetoAtomicamente() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_TEAM,
                        "{\"dominio\":\"Marketing\",\"foco\":\"Tráfego\",\"arquiteto\":"
                                + "{\"name\":\"Aqua\",\"role\":\"arquiteto\","
                                + "\"adapterType\":\"claude-code\",\"model\":\"sonnet\"}}",
                        "Criar time Marketing/Tráfego com arquiteta Aqua", null));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "nenhum time cobre aquisição paga", null, ActorType.BOARD, UUID.randomUUID());

        com.orquestrador.org.team.Team time = teams
                .findByCompanyIdAndDominioAndFoco(companyId, "Marketing", "Tráfego").orElseThrow();
        assertThat(time.getArquitetoAgentId()).as("nasce COM arquiteto").isNotNull();
        Agent aqua = agents.findById(time.getArquitetoAgentId()).orElseThrow();
        assertThat(aqua.getName()).isEqualTo("Aqua");
        assertThat(aqua.getTeamId()).as("arquiteto é MEMBRO do time (ADR-0017)")
                .isEqualTo(time.getId());
    }

    /** D7: alvo sem o arquiteto → 409 acionável, NADA criado (nem o time seco — atômico). */
    @Test
    void createTeamSemArquitetoE409SemMeioTermo() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_TEAM,
                        "{\"dominio\":\"Marketing\",\"foco\":\"Tráfego\"}",
                        "Criar time acéfalo", null));

        assertThatThrownBy(() -> approvals.decide(companyId, proposta.getId(),
                ApprovalDecision.APPROVE, "precisa existir", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("arquiteto");

        assertThat(teams.findByCompanyIdAndDominioAndFoco(companyId, "Marketing", "Tráfego"))
                .as("rollback total: o time acéfalo NÃO nasce").isEmpty();
    }

    /**
     * R10-A/D1-D3-D7 (propose-then-commit de molde): a 5ª ação do gate — CREATE_ROLE_TEMPLATE. O
     * molde é GLOBAL (o CEO mexe no catálogo → gate por ALCANCE, ADR-0017). A versão nasce só no
     * APPROVE (teto D7: nada persiste enquanto PENDING) e carrega proposedBy=CEO / approvedBy=dono
     * — a governança que o hardcode proposedBy=approvedBy=ator era um stand-in.
     */
    @Test
    void createRoleTemplateAprovadoNasceGlobalComProposerDistintoDoApprover() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        UUID dono = UUID.randomUUID();
        String role = "revisor-de-contratos-" + UUID.randomUUID().toString().substring(0, 8);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ROLE_TEMPLATE,
                        "{\"tipoFuncao\":\"REVISAO_QA\",\"roleEspecifica\":\"" + role + "\","
                                + "\"adapterType\":\"claude-code\",\"model\":\"opus\",\"effort\":\"high\","
                                + "\"skillKeys\":[\"tdd\"],\"reportsToDefault\":\"LIDERANCA\","
                                + "\"changeDescription\":\"preset de revisor juridico\"}",
                        "Criar molde revisor-de-contratos", null));

        // teto D7 (G5): enquanto PENDING, NENHUM molde nasce — o gate É o teto.
        assertThat(roleTemplates.findByCompanyIdIsNullAndRoleEspecifica(role)).isEmpty();

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "o time juridico precisa de um preset de revisor", null, ActorType.BOARD, dono);

        RoleTemplate molde = roleTemplates.findByCompanyIdIsNullAndRoleEspecifica(role).orElseThrow();
        assertThat(molde.getCompanyId()).as("molde é GLOBAL (company_id NULL)").isNull();
        RoleTemplateVersion v1 = roleTemplateVersions.findById(molde.getCurrentVersionId()).orElseThrow();
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getStatus()).isEqualTo("APPROVED");
        assertThat(v1.getProposedBy()).as("o CEO propôs").isEqualTo(ceoId);
        assertThat(v1.getApprovedBy()).as("o dono aprovou").isEqualTo(dono);
    }

    /**
     * ADR-0037/D2: a 2ª fábrica de órfãs fecha NA ORIGEM — a proposta CREATE_ISSUE carrega o
     * TIME (dominio/foco) e o APPROVE resolve time→arquiteto: a issue NASCE com dono.
     */
    @Test
    void createIssueComTimeNasceComDonoPeloGate() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        UUID arquitetoId = timeComArquiteto(companyId, "Dev", "Landing", "Ana Arquiteta");
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Dev\",\"foco\":\"Landing\",\"title\":\"Landing nova\"}",
                        "Landing nova", "backlog"));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "a landing atual não converte", null, ActorType.BOARD, UUID.randomUUID());

        assertThat(issues.findByCompanyId(companyId)).anySatisfy(issue -> {
            assertThat(issue.getTitle()).isEqualTo("Landing nova");
            assertThat(issue.getAssigneeAgentId())
                    .as("nada que o CEO cria nasce sem dono (D2)").isEqualTo(arquitetoId);
        });
    }

    /** D2: proposta sem time / corrente quebrada → 409 ACIONÁVEL no decide; segue PENDING. */
    @Test
    void createIssueSemTimeOuCorrenteQuebradaE409EPropostaSeguePending() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);

        // sem dominio/foco no alvo (lacuna histórica da ADR-0018, agora fechada)
        Approval semTime = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"title\":\"Órfã\"}", "Órfã", "backlog"));
        assertThatThrownBy(() -> approvals.decide(companyId, semTime.getId(),
                ApprovalDecision.APPROVE, "precisa existir", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("dominio/foco");
        assertThat(approvalRepo.findByIdAndCompanyId(semTime.getId(), companyId).orElseThrow()
                .getStatus()).as("rollback total: segue PENDING").isEqualTo(ApprovalStatus.PENDING);

        // time citado não existe
        Approval timeFantasma = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Mkt\",\"foco\":\"Ads\",\"title\":\"Campanha\"}",
                        "Campanha", "backlog"));
        assertThatThrownBy(() -> approvals.decide(companyId, timeFantasma.getId(),
                ApprovalDecision.APPROVE, "precisa existir", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Mkt");

        assertThat(issues.findByCompanyId(companyId)).as("nada foi criado").isEmpty();
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

    /**
     * A4 (adendo ADR-0031): o pedido protocolado carrega o MOTOR sugerido (o Executor não mexe —
     * 1:1 com a estratégia) e o hire do APPROVE o honra; skills seguem SOMENTE-LEITURA no menu.
     */
    @Test
    void aproveHonraOMotorProtocoladoNoAlvo() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.HIRE_AGENT,
                        "{\"name\":\"Vega\",\"role\":\"executor\",\"adapterType\":\"claude-code\","
                                + "\"model\":\"opus\",\"effort\":\"high\","
                                + "\"racional\":\"skills tdd; motor claude-code; time de produto\"}",
                        "Contratar Vega", null));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "estrategia aprovada no dossie", null, ActorType.BOARD, UUID.randomUUID());

        Agent vega = agents.findByCompanyId(companyId).stream()
                .filter(agent -> agent.getName().equals("Vega")).findFirst().orElseThrow();
        assertThat(vega.getAdapterType()).isEqualTo("claude-code");
        assertThat(vega.getModel()).isEqualTo("opus");
        assertThat(vega.getEffort()).isEqualTo("high");
    }

    /**
     * A4 (palavras do dono): o CEO tem viés de motor — no menu do hire o MOTOR é livremente
     * editável pelo dono, sem rejeitar nem voltar ao Planejador. O override do decide VENCE o alvo.
     */
    @Test
    void donoSobrescreveOMotorNoAproveSemRejeitar() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.HIRE_AGENT,
                        "{\"name\":\"Sirius\",\"role\":\"executor\",\"adapterType\":\"claude-code\","
                                + "\"model\":\"opus\"}", "Contratar Sirius", null));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "prefiro o motor codex neste time", null, ActorType.BOARD, UUID.randomUUID(),
                new ApprovalService.EngineOverride("codex", "gpt-5-codex", "medium"));

        Agent sirius = agents.findByCompanyId(companyId).stream()
                .filter(agent -> agent.getName().equals("Sirius")).findFirst().orElseThrow();
        assertThat(sirius.getAdapterType()).isEqualTo("codex");   // a mão soberana venceu
        assertThat(sirius.getModel()).isEqualTo("gpt-5-codex");
        assertThat(sirius.getEffort()).isEqualTo("medium");
        assertThat(sirius.getRole()).isEqualTo("executor");       // o RESTO do pedido, intocado
        assertThat(approvalRepo.findById(proposta.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.APPROVED);              // editar motor não exige rejeição
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
        timeComArquiteto(companyId, "Produto", "Nicho", "Ana Arquiteta");
        // ADR-0037 (D2): o alvo carrega o time — issue de direção também nasce com dono.
        ProposalScanner.Proposal direcao = new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                "{\"dominio\":\"Produto\",\"foco\":\"Nicho\",\"title\":\"Focar no nicho dental\"}",
                "Focar no nicho dental", "direcao-roadmap");
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

    /**
     * F-C1 (C12 — writer D11 ACESO na porta pré-hoc): o CEO propõe citando o {@code #N}, o dono
     * sela com {@code supersedesSeq} — a nova direção aponta a velha e a carta se AUTO-CURA na
     * hora (o leitor já filtrava supersedidas; faltava o elo de escrita).
     */
    @Test
    void donoSelaSupersedeCitadoEACartaSeAutoCura() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        timeComArquiteto(companyId, "Produto", "Nicho", "Ana Arquiteta");
        Approval antiga = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Produto\",\"foco\":\"Nicho\",\"title\":\"Focar no nicho dental\"}",
                        "Focar no nicho dental", "direcao-roadmap"));
        approvals.decide(companyId, antiga.getId(), ApprovalDecision.APPROVE,
                "o nicho dental tem dor aguda", null, ActorType.BOARD, UUID.randomUUID());
        Decision direcaoAntiga = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);
        assertThat(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody()).contains("Focar no nicho dental");

        Approval nova = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Produto\",\"foco\":\"Nicho\",\"title\":\"Pivotar para veterinaria\"}",
                        "Pivotar para veterinaria (supera a #" + direcaoAntiga.getSeq() + ")",
                        "direcao-roadmap"));
        approvals.decide(companyId, nova.getId(), ApprovalDecision.APPROVE,
                "o dental saturou; a premissa caiu", null, ActorType.BOARD, UUID.randomUUID(),
                null, direcaoAntiga.getSeq());

        Decision direcaoNova = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> decision.getOQueMudou().contains("Pivotar")).findFirst().orElseThrow();
        assertThat(direcaoNova.getSupersedesId()).isEqualTo(direcaoAntiga.getId());
        String carta = memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody();
        assertThat(carta).contains("Pivotar para veterinaria");
        assertThat(carta).as("a supersedida sai da carta sozinha (auto-cura do leitor)")
                .doesNotContain("Focar no nicho dental");
    }

    /** C12: endereço #N fantasma no sela → 409 acionável ANTES de qualquer efeito; segue PENDING. */
    @Test
    void supersedeComEnderecoFantasmaE409EPropostaSeguePending() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null, hireNix());

        assertThatThrownBy(() -> approvals.decide(companyId, proposta.getId(),
                ApprovalDecision.APPROVE, "runway curto", null, ActorType.BOARD, UUID.randomUUID(),
                null, 99))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("#99");
        assertThat(approvalRepo.findById(proposta.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(agents.findByCompanyId(companyId)).as("nada disparou").hasSize(1); // só o CEO
    }

    @Test
    void areaCriativaCommitaNaHoraComPreferencia() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        timeComArquiteto(companyId, "Design", "Home", "Bia Arquiteta");
        ProposalScanner.Proposal criativa = new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                "{\"dominio\":\"Design\",\"foco\":\"Home\",\"title\":\"Refazer a home com tom leve\"}",
                "Refazer a home", "design-visual");
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

    /**
     * F-AT1 (grill agente-como-tool, D5): o pedido de skill do EXECUTOR audita distinto do
     * CEO_PROPOSED genérico — SKILL_INSTALL_REQUESTED, ator=AGENTE (quem pediu).
     */
    @Test
    void pedidoDeSkillAuditaSkillInstallRequestedNaoCeoProposed() {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId);
        UUID issueId = issueService.create(companyId, "tarefa", null).getId();

        Approval pedido = approvals.propose(companyId, executorId, null, null,
                pedidoSkill("tdd-" + UUID.randomUUID(), issueId));

        assertThat(pedido.getActionType()).isEqualTo(ApprovalActionType.SKILL_INSTALL_REQUEST);
        assertThat(activities.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo(ActivityAction.SKILL_INSTALL_REQUESTED.name());
                    assertThat(entry.getActorType()).isEqualTo(ActorType.AGENT.name());
                    assertThat(entry.getActorId()).isEqualTo(executorId);
                })
                .noneSatisfy(entry -> assertThat(entry.getAction()).isEqualTo(ActivityAction.CEO_PROPOSED.name()));
    }

    /**
     * F-AT1 (D2/D3): a 6ª ação do gate — SKILL_INSTALL_REQUEST. APPROVE dispara
     * {@code SkillSetService.attachToIssue} (reuso puro, install-on-attach ADR-0003 já cobre o
     * AVAILABLE) no escopo da Issue, para o AGENTE que pediu — nunca o aprovador.
     */
    @Test
    void skillInstallRequestAprovadoAnexaASkillNaIssueParaOAgenteQuePediu() throws Exception {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId);
        UUID issueId = issueService.create(companyId, "tarefa", null).getId();
        String skillKey = "tdd-" + UUID.randomUUID();
        Skill skill = skillGlobalDisponivel(skillKey); // AINDA não instalada nesta company

        Approval pedido = approvals.propose(companyId, executorId, null, null, pedidoSkill(skillKey, issueId));

        assertThat(companySkills.findByCompanyIdAndSkillId(companyId, skill.getId()))
                .as("teto D7-like: nada instala enquanto PENDING").isEmpty();

        approvals.decide(companyId, pedido.getId(), ApprovalDecision.APPROVE,
                "o executor bateu na parede sem essa skill", null, ActorType.BOARD, UUID.randomUUID());

        assertThat(companySkills.findByCompanyIdAndSkillId(companyId, skill.getId()))
                .as("install-on-attach: AVAILABLE instalou na company").isPresent();
        assertThat(skillSets.resolveForRun(companyId, executorId, issueId))
                .as("a união efetiva do executor pra esta Issue já inclui a skill pedida")
                .anySatisfy(version -> assertThat(version.getName()).isEqualTo(skillKey));
    }

    /**
     * F-C2 (C6): a 7ª ação do gate — GRADUATE_PRINCIPLE. O CEO propõe a FRASE (C2: a entrada do
     * Playbook é AUTORADA), o dono sela; o dispatch fabrica a Decisão {@code grau=PRINCIPIO} pela
     * fábrica única (approval_id NOT NULL de graça) com a linhagem C5 em evidência — e ela é a
     * ÚNICA Decisão da passagem (o princípio É o registro do gate; não nasce uma irmã DECISAO).
     */
    @Test
    void graduatePrincipleAprovadoFabricaOPrincipioComoUnicaDecisaoDaPassagem() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.GRADUATE_PRINCIPLE,
                        "{\"frase\":\"Toda campanha nasce com meta de CAC explicita\","
                                + "\"graduadaDe\":[1,2,3]}",
                        "Graduar principio de growth", "growth"));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.APPROVE,
                "3 decisoes de growth repetiram o mesmo criterio", null,
                ActorType.BOARD, UUID.randomUUID());

        List<Decision> daPassagem = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> proposta.getId().equals(decision.getApprovalId()))
                .toList();
        assertThat(daPassagem).as("uma passagem, UMA Decisão — o princípio É o registro").hasSize(1);
        Decision principio = daPassagem.get(0);
        assertThat(principio.getGrau()).isEqualTo(DecisionGrau.PRINCIPIO);
        assertThat(principio.getOQueMudou())
                .isEqualTo("Toda campanha nasce com meta de CAC explicita"); // a frase, não [APPROVE …]
        assertThat(principio.getOrigem()).isEqualTo(DecisionOrigem.PROPOSTA_CEO);
        assertThat(principio.getEvidencia()).contains("#1").contains("#2").contains("#3"); // C5

        String playbook = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK)
                .orElseThrow().getBody();
        assertThat(playbook).contains("Toda campanha nasce com meta de CAC explicita");
    }

    /**
     * F-C2 (prova extra, deriva do C2): a frase EDITADA pelo dono no card VENCE a proposta do CEO
     * — writer-of-record de verdade (mão soberana, padrão A4). E o supersede selado (D11) aponta
     * DO princípio: graduar já pode aposentar a direção que ele destila — a carta se auto-cura e
     * o playbook nasce na MESMA passagem.
     */
    @Test
    void fraseEditadaPeloDonoVenceAPropostaEOSupersedeSelaDoPrincipio() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        timeComArquiteto(companyId, "Produto", "Nicho", "Ana Arquiteta");
        Approval direcao = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.CREATE_ISSUE,
                        "{\"dominio\":\"Produto\",\"foco\":\"Nicho\",\"title\":\"Focar no nicho dental\"}",
                        "Focar no nicho dental", "direcao-roadmap"));
        approvals.decide(companyId, direcao.getId(), ApprovalDecision.APPROVE,
                "dor aguda e ticket alto", null, ActorType.BOARD, UUID.randomUUID());
        Decision direcaoAntiga = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).get(0);

        Approval graduacao = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.GRADUATE_PRINCIPLE,
                        "{\"frase\":\"Nicho dental primeiro\",\"graduadaDe\":["
                                + direcaoAntiga.getSeq() + "]}",
                        "Graduar principio do nicho", "direcao-roadmap"));
        approvals.decide(companyId, graduacao.getId(), ApprovalDecision.APPROVE,
                "a direcao virou padrao de decisao", null, ActorType.BOARD, UUID.randomUUID(),
                null, direcaoAntiga.getSeq(),
                "Nicho dental sempre — so sair com dado de saturacao");

        Decision principio = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> graduacao.getId().equals(decision.getApprovalId()))
                .findFirst().orElseThrow();
        assertThat(principio.getOQueMudou())
                .as("a frase final é a do dono, não a proposta")
                .isEqualTo("Nicho dental sempre — so sair com dado de saturacao");
        assertThat(principio.getSupersedesId()).isEqualTo(direcaoAntiga.getId());

        String carta = memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody();
        assertThat(carta).as("a direção destilada saiu da carta (auto-cura D11, mesmo sendo a última)")
                .doesNotContain("Focar no nicho dental");
        assertThat(carta).as("princípio NÃO polui a carta — a superfície dele é o playbook (C9)")
                .doesNotContain("Nicho dental sempre");
        assertThat(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK)
                .orElseThrow().getBody()).contains("Nicho dental sempre");
    }

    /** REJECT de graduação não nasce princípio: registra a recusa como Decisão comum (grau=DECISAO). */
    @Test
    void graduatePrincipleRejeitadoRegistraRecusaSemPrincipio() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.GRADUATE_PRINCIPLE,
                        "{\"frase\":\"Todo copy fala com dono de clinica, nunca com dentista\"}",
                        "Graduar principio de copy", "growth"));

        approvals.decide(companyId, proposta.getId(), ApprovalDecision.REJECT,
                "dois dos tres casos sao a mesma campanha — padrao ainda nao existe",
                "espere mais evidencia", ActorType.BOARD, UUID.randomUUID());

        Decision registro = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> proposta.getId().equals(decision.getApprovalId()))
                .findFirst().orElseThrow();
        assertThat(registro.getGrau()).isEqualTo(DecisionGrau.DECISAO); // recusa não é princípio
        assertThat(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK))
                .as("playbook não nasce de recusa").isEmpty();
    }

    /** Alvo sem a frase (C2: a entrada é AUTORADA) → 409 acionável no decide; segue PENDING. */
    @Test
    void graduatePrincipleSemFraseE409EPropostaSeguePending() {
        UUID companyId = novaCompany();
        UUID ceoId = contrataCeu(companyId);
        Approval proposta = approvals.propose(companyId, ceoId, null, null,
                new ProposalScanner.Proposal(ApprovalActionType.GRADUATE_PRINCIPLE,
                        "{\"graduadaDe\":[1,2]}", "Graduar principio", "growth"));

        assertThatThrownBy(() -> approvals.decide(companyId, proposta.getId(),
                ApprovalDecision.APPROVE, "padrao claro em growth", null,
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("frase");
        assertThat(approvalRepo.findById(proposta.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId)).isEmpty();
    }

    /** Elo vazio (skillKey some do catálogo entre o pedido e a decisão) → 409 acionável, PENDING intacta. */
    @Test
    void skillKeyDesconhecidoNoAproveE409EPropostaSeguePending() {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId);
        UUID issueId = issueService.create(companyId, "tarefa", null).getId();
        Approval pedido = approvals.propose(companyId, executorId, null, null,
                pedidoSkill("fantasma-" + UUID.randomUUID(), issueId));

        assertThatThrownBy(() -> approvals.decide(companyId, pedido.getId(), ApprovalDecision.APPROVE,
                "aprovado sem checar", null, ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("catalogo global");
        assertThat(approvalRepo.findById(pedido.getId()).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.PENDING);
    }
}
