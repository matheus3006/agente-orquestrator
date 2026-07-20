package com.orquestrador.memory;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A carta materializada (ADR-0018/0019): a versão CORRENTE de company_memory vira
 * {@code .claude/CLAUDE.md} no workspace do Run de turno; sem carta, o arquivo some
 * (idempotente — nunca serve carta velha).
 */
@SpringBootTest
@ActiveProfiles("test")
class CompanyMemoryMaterializationTest {

    @Autowired
    private CompanyMemoryMaterializer materializer;
    @Autowired
    private CompanyMemoryRepository memories;
    @Autowired
    private DecisionLog decisionLog;
    @Autowired
    private MemoryQuery memoryQuery;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private IssueService issues;

    @TempDir
    Path workDir;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void materializaAVersaoCorrenteERemoveQuandoNaoHaCarta() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");

        // Sem carta: nada é escrito.
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        assertThat(Files.exists(claudeMd)).isFalse();

        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta v1", 1));
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        assertThat(Files.readString(claudeMd)).isEqualTo("# Carta v1");

        // Versão mais nova VENCE (append-only: v2 não apaga v1 no banco, mas o arquivo é o corrente).
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta v2", 2));
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        assertThat(Files.readString(claudeMd)).isEqualTo("# Carta v2");

        // Carta de OUTRA company nunca vaza; sem carta própria o arquivo é REMOVIDO.
        UUID outraCompany = novaCompany();
        materializer.materialize(workDir, ".claude/CLAUDE.md", outraCompany, MemoryAudience.CEO);
        assertThat(Files.exists(claudeMd)).isFalse();
    }

    @Test
    void oArquivoEParametroDaConvencaoDoAdapter() throws Exception {
        // ADR-0032: no motor codex a carta vira AGENTS.md na raiz (corpo VERBATIM, irmão exato
        // do .claude/CLAUDE.md) — o arquivo vem da WorkspaceConvention do adapter, com o MESMO
        // contrato reescreve-ou-remove (nunca serve carta velha).
        UUID companyId = novaCompany();
        Path agentsMd = workDir.resolve("AGENTS.md");

        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta codex", 1));
        materializer.materialize(workDir, "AGENTS.md", companyId, MemoryAudience.CEO);
        assertThat(Files.readString(agentsMd)).isEqualTo("# Carta codex");
        assertThat(Files.exists(workDir.resolve(".claude/CLAUDE.md"))).isFalse();

        // Sem carta própria (outra company), o AGENTS.md é REMOVIDO.
        materializer.materialize(workDir, "AGENTS.md", novaCompany(), MemoryAudience.CEO);
        assertThat(Files.exists(agentsMd)).isFalse();
    }

    @Test
    void bytesEOrdemDaDietaCanonicaSaoIdenticosNosPathsClaudeECodex() throws Exception {
        UUID companyId = novaCompany();
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Carta canonica\n- direcao LARANJA-17", 1));
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.PLAYBOOK,
                "# Playbook canonico\n- principio VERDE-42", 1));

        String bytesCeo = "# Carta canonica\n- direcao LARANJA-17"
                + "\n\n"
                + "# Playbook canonico\n- principio VERDE-42";
        String bytesExecutor = "# Playbook canonico\n- principio VERDE-42";

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        materializer.materialize(workDir, "AGENTS.md", companyId, MemoryAudience.CEO);
        assertThat(Files.readString(workDir.resolve(".claude/CLAUDE.md"))).isEqualTo(bytesCeo);
        assertThat(Files.readString(workDir.resolve("AGENTS.md"))).isEqualTo(bytesCeo);

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR);
        materializer.materialize(workDir, "AGENTS.md", companyId, MemoryAudience.EXECUTOR);
        assertThat(Files.readString(workDir.resolve(".claude/CLAUDE.md"))).isEqualTo(bytesExecutor);
        assertThat(Files.readString(workDir.resolve("AGENTS.md"))).isEqualTo(bytesExecutor);
    }

    /**
     * O SPLIT do sempre-on (F-C3, C9): as duas pontas recebem DIETAS diferentes da mesma memória.
     * O CEO recebe a carta (direções vigentes) + os princípios; o EXECUTOR recebe os PRINCÍPIOS e
     * NUNCA a carta — direção do dono não é material de executor. Os princípios SÃO o índice enxuto
     * (frases terças, dono-seladas) e trazem o {@code #N} que destrava o {@code memory_fetch}.
     */
    @Test
    void oSplitDoSempreOn_executorRecebePrincipiosNuncaACarta() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Carta de interesses da empresa\n- #3 cobrar por assento", 1));
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.PLAYBOOK,
                "# Playbook da empresa\n- #7 sempre validar canal antes de escalar", 1));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        assertThat(Files.readString(claudeMd))
                .as("o CEO recebe as DUAS superfícies")
                .contains("Carta de interesses").contains("#3 cobrar por assento")
                .contains("Playbook da empresa").contains("#7 sempre validar canal");

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR);
        assertThat(Files.readString(claudeMd))
                .as("o EXECUTOR recebe os princípios — e a carta NÃO entra")
                .contains("Playbook da empresa").contains("#7 sempre validar canal")
                .doesNotContain("Carta de interesses").doesNotContain("cobrar por assento");
    }

    /** Sem princípio selado, o executor não ganha arquivo nenhum (nunca serve dieta vazia/velha). */
    @Test
    void semPlaybookOExecutorNaoRecebeArquivo() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta v1", 1));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR);
        assertThat(Files.exists(claudeMd)).isFalse();

        // E o arquivo de um run ANTERIOR do CEO é REMOVIDO quando a dieta do executor está vazia.
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);
        assertThat(Files.exists(claudeMd)).isTrue();
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR);
        assertThat(Files.exists(claudeMd)).isFalse();
    }

    /**
     * O split C9 vivo, no RUN: até a F-C3 o executor de uma Issue não recebia memória nenhuma
     * ("em run de Issue o executor não o chama"). Agora recebe os PRINCÍPIOS — e a carta continua
     * fora do workspace dele.
     */
    @Test
    void runDeExecutorMaterializaOsPrincipiosSemACarta() throws Exception {
        UUID companyId = novaCompany();
        Agent executor = new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null);
        executor.configureRuntime("process", "echo ok", "LOCAL");
        UUID executorId = agents.save(executor).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Carta de interesses da empresa\n- #3 cobrar por assento", 1));
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.PLAYBOOK,
                "# Playbook da empresa\n- #7 validar canal antes de escalar", 1));

        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path claudeMd = workspaces.resolve(companyId, executorId, issueId).resolve(".claude/CLAUDE.md");
        assertThat(Files.readString(claudeMd))
                .contains("#7 validar canal antes de escalar")
                .doesNotContain("cobrar por assento");
    }

    /**
     * O feedback R19-A no RUN REAL (dispatchOnce, motor {@code process}): uma Issue com uma Decisão
     * de REJECT ancorada nela → o workspace do run do executor recebe o feedback LITERAL, com o
     * {@code #N}. Prova de ponta-a-ponta de que o issueId FLUI do {@code RunContext} até a
     * materialização (o gap exato que a fatia fecha).
     */
    @Test
    void runRealDoExecutorMaterializaOFeedbackDoRejectDaIssue() throws Exception {
        UUID companyId = novaCompany();
        Agent executor = new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null);
        executor.configureRuntime("process", "echo ok", "LOCAL");
        UUID executorId = agents.save(executor).getId();
        UUID issueId = issues.create(companyId, "Login flow", null).getId();
        // O dono reprovou a entrega no review: a Decisão do reject nasce ancorada NA issue.
        Decision reject = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #1 Login flow",
                "faltou tratar o refresh token expirado", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));

        wakeups.enqueue(companyId, executorId, issueId, "retrabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path claudeMd = workspaces.resolve(companyId, executorId, issueId).resolve(".claude/CLAUDE.md");
        assertThat(Files.readString(claudeMd))
                .as("o re-run do executor recebe o feedback literal do reject, com o #N")
                .contains("#" + reject.getSeq())
                .contains("faltou tratar o refresh token expirado");
    }

    /**
     * A FATIA C do fecho do v1 (R19-A, ADR-0011 emendada): o feedback do dono no review NÃO é mais
     * "editado na Issue antes do REOPEN" (premissa morta) — ele já É uma Decisão endereçada por
     * {@code #N}, ancorada na review E na issue. A dieta NOVA e DIRECIONADA do executor PUXA
     * (materializa) as Decisões ancoradas NAQUELA issue: o agente RECEBE o motivo do reject no
     * re-run, sem depender de escolher buscar (PUSH, não pull — feedback de reject não é opcional).
     */
    @Test
    void executorDeIssueRecebeOFeedbackDoRejectAncoradoNaIssue() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        UUID issueId = issues.create(companyId, "Login flow", null).getId();
        Decision reject = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #1 Login flow",
                "faltou tratar o refresh token expirado", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR, issueId);

        assertThat(Files.readString(claudeMd))
                .as("o executor recebe o racional do reject com o endereço #N")
                .contains("#" + reject.getSeq())
                .contains("faltou tratar o refresh token expirado");
    }

    /**
     * Issue SEM Decisão ancorada: a dieta do executor fica EXATAMENTE a durável — byte-idêntica ao
     * run sem issue. O caminho novo é ADITIVO e só acende quando há feedback (nenhuma seção nasce
     * do nada), preservando o contrato de hoje.
     */
    @Test
    void issueSemDecisoesAncoradas_dietaIdenticaAoDuravel() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.PLAYBOOK,
                "# Playbook da empresa\n- #7 validar canal antes de escalar", 1));
        UUID issueSemDecisoes = issues.create(companyId, "tarefa nova", null).getId();

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR, issueSemDecisoes);
        String comIssueId = Files.readString(claudeMd);

        Files.deleteIfExists(claudeMd);
        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR);
        String semIssue = Files.readString(claudeMd);

        assertThat(comIssueId)
                .as("issue sem feedback → dieta durável intocada, sem seção de issue")
                .isEqualTo(semIssue)
                .doesNotContain("Feedback do dono");
    }

    /**
     * Todas as Decisões ancoradas na issue entram, com seu {@code #N} e em ordem de ENDEREÇO — o
     * REJECT do review ({@code anchoredToReview} grava o issueId) E as do caso B
     * ({@code anchoredToIssue}). Feedback de OUTRA issue nunca vaza no workspace deste run.
     */
    @Test
    void materializaTodasAsDecisoesDaIssueEmOrdem_semVazarDeOutraIssue() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        UUID issueId = issues.create(companyId, "Login flow", null).getId();
        UUID outraIssue = issues.create(companyId, "Outra tarefa", null).getId();

        Decision reject = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #1 Login flow",
                "faltou tratar o refresh token expirado", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));
        Decision casoB = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "corrigir o contrato do endpoint",
                "o front espera 204 sem corpo", RacionalTipo.LOGICO, false)
                .anchoredToIssue(issueId));
        decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #2 Outra tarefa",
                "SIGILO_DE_OUTRA_ISSUE", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), outraIssue));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR, issueId);
        String dieta = Files.readString(claudeMd);

        assertThat(dieta)
                .contains("#" + reject.getSeq()).contains("faltou tratar o refresh token expirado")
                .contains("#" + casoB.getSeq()).contains("o front espera 204 sem corpo")
                .doesNotContain("SIGILO_DE_OUTRA_ISSUE");
        assertThat(dieta.indexOf("#" + reject.getSeq() + " "))
                .as("ordem de endereço: o #N menor (o reject) vem antes do caso B")
                .isLessThan(dieta.indexOf("#" + casoB.getSeq() + " "));
    }

    /**
     * A "SUPERSEDIDA" tem de chegar ao leitor que a ADR-0049 cita como MOTIVO de fabricar a Decisão
     * da reabertura (conferência dos PRs #51/#52, 🔵). O argumento do §3 é: não basta o
     * activity_log, porque a Decisão é o que o executor LÊ na dieta do re-run — um
     * {@code [APPROVE REVIEW] #N} sem qualificação seguiria dizendo a todo agente futuro que a
     * entrega foi aceita.
     *
     * <p>Só que o filtro de superseded existia <b>apenas na projeção da carta</b>
     * ({@code CompanyMemoryConsolidator}). Esta seção renderizava TODAS as ancoradas em ordem de
     * seq, sem marca nenhuma — então o re-run continuava lendo o APPROVE como se valesse, ao lado
     * da reabertura que o desfez. O argumento da ADR ficava meio respondido.
     */
    @Test
    void decisaoSUPERSEDIDA_chegaAoExecutorMARCADA_naoComoSeAindaValesse() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        UUID issueId = issues.create(companyId, "Login flow", null).getId();

        Decision approve = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.AGENTE, "[APPROVE REVIEW] #1 Login flow",
                "entrega redonda", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));
        Decision reabertura = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REABERTURA] #1 Login flow",
                "o teste do caminho triste não existe", RacionalTipo.LOGICO, false)
                .superseding(approve.getId())
                .anchoredToReview(UUID.randomUUID(), issueId));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR, issueId);
        String dieta = Files.readString(claudeMd);

        // As duas continuam VISÍVEIS — o rastro é a metade da D-ORG-2 que paga pela outra.
        assertThat(dieta).contains("#" + approve.getSeq()).contains("#" + reabertura.getSeq());
        String linhaDoApprove = dieta.lines()
                .filter(linha -> linha.startsWith("- #" + approve.getSeq() + " "))
                .findFirst().orElseThrow();
        assertThat(linhaDoApprove)
                .as("o executor precisa ler que este APPROVE foi DESFEITO, não que ele vale")
                .contains("SUPERSEDIDA");
    }

    /**
     * A ponta do CEO fica INALTERADA (R19-A): o turno tem issueId nulo, então nenhuma seção de
     * issue entra — mesmo quando a company TEM Decisões ancoradas em issues. O CEO recebe carta +
     * playbook, como antes; o feedback de issue é material do EXECUTOR daquela issue, não do CEO.
     */
    @Test
    void ceoNaoRecebeSecaoDeIssue_mesmoComDecisoesAncoradasNaCompany() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Carta de interesses da empresa\n- #3 cobrar por assento", 1));
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.PLAYBOOK,
                "# Playbook da empresa\n- #7 validar canal antes de escalar", 1));
        UUID issueId = issues.create(companyId, "Login flow", null).getId();
        decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #1 Login flow",
                "FEEDBACK_QUE_NAO_E_DO_CEO", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.CEO);

        assertThat(Files.readString(claudeMd))
                .contains("Carta de interesses").contains("#3 cobrar por assento")
                .contains("Playbook da empresa").contains("#7 validar canal antes de escalar")
                .doesNotContain("Feedback do dono").doesNotContain("FEEDBACK_QUE_NAO_E_DO_CEO");
    }

    /**
     * A CONVIVÊNCIA (R19-A × C10.b): o MESMO reject que o materializer ENTREGA ao executor
     * (grau=DECISAO, tipo OPERACIONAL) segue RECUSADO no {@code memory_fetch}. O caminho novo é
     * DIRECIONADO (só a issue do run) e NÃO alarga o trilho JIT selado — as duas decisões do dono
     * (R17-B) convivem por construção, sem tensão a resolver.
     */
    @Test
    void oFeedbackEntregueConviveComOMemoryFetchSelado() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");
        UUID issueId = issues.create(companyId, "Login flow", null).getId();
        Decision reject = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backend",
                DecisionTipo.OPERACIONAL, DecisionOrigem.DONO_NO_GATE, "[REJECT REVIEW] #1 Login flow",
                "faltou tratar o refresh token expirado", RacionalTipo.LOGICO, false)
                .anchoredToReview(UUID.randomUUID(), issueId));

        materializer.materialize(workDir, ".claude/CLAUDE.md", companyId, MemoryAudience.EXECUTOR, issueId);

        assertThat(Files.readString(claudeMd))
                .as("o executor RECEBE o feedback pelo caminho direcionado (PUSH)")
                .contains("faltou tratar o refresh token expirado");
        assertThat(memoryQuery.fetch(companyId, reject.getSeq()))
                .as("o MESMO #N segue FORA do memory_fetch (log cru board-only, C10.b intocada)")
                .isEmpty();
    }

    @Test
    void runDeTurnoMaterializaACartaNoWorkspaceDoCeo() throws Exception {
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Interesses da empresa\n- foco no MVP", 1));

        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path claudeMd = workspaces.resolve(companyId, ceoId, null).resolve(".claude/CLAUDE.md");
        assertThat(Files.readString(claudeMd)).contains("foco no MVP");
    }
}
