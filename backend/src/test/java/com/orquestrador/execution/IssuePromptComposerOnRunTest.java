package com.orquestrador.execution;

import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.EngineContainer;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.roletemplate.RoleTemplateVersion;
import com.orquestrador.org.roletemplate.RoleTemplateVersionRepository;
import com.orquestrador.work.issue.IssueConversaTestSupport;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O conserto do bug #1 (R22-A, ADR-0037 emendada): o run de ISSUE ganha compositor de prompt —
 * a simetria que faltava do {@code composePrompt} do turno. O agente contratado pelo gate
 * (runtime_command NULL, correto pós-decisão 2) recebe a ISSUE como instrução: {@code #N} +
 * título + descrição (+ estratégia quando anotada — teste próprio). Nunca null: sem instrução o
 * run seria o no-op do DONE fantasma.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(classes = IssuePromptComposerOnRunTest.PromptProbeConfig.class)
class IssuePromptComposerOnRunTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private RoleTemplateVersionRepository versions;
    @Autowired
    private PromptProbe probe;

    /** As 3 checagens nível-plano que a V32 semeia no papel arquiteto (mesmo shape, mesmo texto). */
    private static final String CHECKS_ARQUITETO =
            "[{\"id\":\"seam\",\"text\":\"O plano aponta o seam/encaixe (arquivo/módulo) que vai tocar?\"},"
            + "{\"id\":\"file-line\",\"text\":\"Cada tarefa/passo cita arquivo:linha concreto?\"},"
            + "{\"id\":\"test\",\"text\":\"O plano tem seção ou menção explícita de teste?\"}]";

    private UUID pinnedVersion(String checksJson) {
        return versions.save(new RoleTemplateVersion(UUID.randomUUID(), UUID.randomUUID(), 1,
                "claude-code", "opus", "ultracode", "[\"tdd\"]", "LIDERANCA", "inicial", "molde",
                null, null, "APPROVED", checksJson)).getId();
    }

    @org.junit.jupiter.api.BeforeEach
    void limpaCapturas() {
        probe.captures.clear();
    }

    @Test
    void runDeIssueComAdapterRicoSemRuntimeCommandRecebePromptCompostoDaIssue() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        // A contratação do gate: runtimeCommand NULL é VÁLIDO para agente de IA (decisão 2 do dono).
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Arquiteto", "architect", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Precificar o produto",
                "Levantar piso de margem e ancorar no valor").getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        PromptProbe.Capture capture = probe.captures.get(0);
        // O prompt composto carrega a IDENTIDADE (#N), o título e a descrição da issue.
        assertThat(capture.command()).isNotBlank();
        assertThat(capture.command()).contains("#1");
        assertThat(capture.command()).contains("Precificar o produto");
        assertThat(capture.command()).contains("Levantar piso de margem e ancorar no valor");
        // O MESMO prompt chega pelo env (o canal do arquétipo process) — o seam é um só.
        assertThat(capture.promptEnv()).isEqualTo(capture.command());
        // Run com instrução real: a issue fecha pelo caminho normal.
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void issueAncoradaAConversaComBastaoAnotadoRecebeAEstrategiaNoBriefing() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Arquiteto", "architect", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Precificar o produto", "descricao").getId();

        // A conversa direcionada da issue (ADR-0037) com o endereço da estratégia ANOTADO (V30)
        // e o arquivo REAL no workspace DA CONVERSA (onde o Planejador o gravou).
        Conversation conversa = conversations.save(
                new Conversation(UUID.randomUUID(), companyId, "#1 — Precificar o produto"));
        String bastaoPath = ".orq/conversa/" + conversa.getId() + "/estrategia.md";
        conversa.anotarBastao(bastaoPath);
        conversations.save(conversa);
        IssueConversaTestSupport.ancorar(issueRepo, companyId, issueId, conversa.getId());
        Path estrategia = workspaces.resolveConversation(companyId, conversa.getId())
                .resolve(bastaoPath);
        Files.createDirectories(estrategia.getParent());
        Files.writeString(estrategia, "piso de margem 75%; ancorar preço no valor, não em custo");

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        String briefing = probe.captures.get(0).command();
        assertThat(briefing).contains("#1");
        assertThat(briefing).contains("Precificar o produto");
        assertThat(briefing).contains("piso de margem 75%; ancorar preço no valor, não em custo");
        assertThat(briefing).contains(bastaoPath); // o agente sabe DE ONDE a estratégia veio
    }

    @Test
    void estrategiaAnotadaMasSumidaNaoCalaAIssueEAvisaNoBriefing() {
        // Fail-soft anti-silêncio: endereço anotado sem arquivo legível → a issue segue
        // instruindo (nunca null) e o briefing SINALIZA a lacuna em vez de escondê-la.
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Arquiteto", "architect", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Precificar o produto", "descricao").getId();
        Conversation conversa = conversations.save(
                new Conversation(UUID.randomUUID(), companyId, "#1 — Precificar o produto"));
        conversa.anotarBastao(".orq/conversa/" + conversa.getId() + "/estrategia.md");
        conversations.save(conversa);
        IssueConversaTestSupport.ancorar(issueRepo, companyId, issueId, conversa.getId());
        // (nenhum arquivo gravado)

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        String briefing = probe.captures.get(0).command();
        assertThat(briefing).contains("#1");
        assertThat(briefing).contains("Precificar o produto");
        assertThat(briefing).containsIgnoringCase("estratégia");
        assertThat(briefing).contains("não encontrada");
    }

    @Test
    void issueSemDescricaoAindaAssimInstruiNuncaNull() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Arquiteto", "architect", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Só título", null).getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        assertThat(probe.captures.get(0).command()).contains("#1");
        assertThat(probe.captures.get(0).command()).contains("Só título");
    }

    @Test
    void processLegitimoComComandoSegueRodandoOScriptEOBriefingChegaPeloEnv() throws Exception {
        // Decisão 2 do dono: runtime_command = SÓ comando de script (arquétipo process). O script
        // segue sendo o command (a issue fecha pelo caminho de sempre) e o briefing composto chega
        // pelo canal do process: o env ORQ_PROMPT (prosa em sh -c seria executada como shell).
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Script", "worker", null);
        agent.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\" > briefing.txt", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Rodar o script", "tarefa mecanica").getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        Path briefing = workspaces.resolve(companyId, agentId, issueId).resolve("briefing.txt");
        assertThat(Files.readString(briefing)).contains("#1");
        assertThat(Files.readString(briefing)).contains("Rodar o script");
    }

    @Test
    void arquitetoComVersaoPinadaComChecks_recebeSecaoDeChecagensNoBriefing() {
        // Uso B / B-i (docs §2/§4.b): o arquiteto vivo está pinado numa versão de molde COM checks (o
        // mesmo shape que a V32 semeia). O briefing dele deve EXIBIR `## Checagens` com os `text` — ele
        // já sabe, ao criar o plano, o que o plano precisa ter. Gate = "tem checks" (auto-escopa: nenhum
        // hard-code de "arquiteto"; só o molde arquiteto é semeado com checks).
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Arquiteto", "architect", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        agent.pinTemplateVersion(pinnedVersion(CHECKS_ARQUITETO));
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Precificar o produto", "descricao").getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        String briefing = probe.captures.get(0).command();
        assertThat(briefing).contains("## Checagens");
        assertThat(briefing).contains("O plano aponta o seam/encaixe (arquivo/módulo) que vai tocar?");
        assertThat(briefing).contains("Cada tarefa/passo cita arquivo:linha concreto?");
        assertThat(briefing).contains("O plano tem seção ou menção explícita de teste?");
        // A seção mora ENTRE a estratégia e o Report (a ordem que o compositor promete).
        assertThat(briefing.indexOf("## Checagens")).isLessThan(briefing.indexOf("## Report"));
    }

    @Test
    void agenteSemPin_naoRecebeSecaoDeChecagens() {
        // Gate = "tem checks": agente sem pin (pinnedTemplateVersionId null) → resolve vazio → sem seção.
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Executor", "executor", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        UUID agentId = agents.save(agent).getId(); // sem pinTemplateVersion
        UUID issueId = issues.create(companyId, "Rodar tarefa", "descricao").getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        assertThat(probe.captures.get(0).command()).doesNotContain("## Checagens");
    }

    @Test
    void versaoPinadaSemChecks_naoRecebeSecaoDeChecagens() {
        // Auto-escopo por dado: papel não-arquiteto pina versão SEM checks (null) → resolve vazio → sem
        // seção. Prova que o gate não é hard-code de "arquiteto" — é a presença de checks na versão.
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "Executor", "executor", null);
        agent.configureRuntime("prompt-probe", null, "LOCAL");
        agent.pinTemplateVersion(pinnedVersion(null));
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "Rodar tarefa", "descricao").getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(probe.captures).hasSize(1);
        assertThat(probe.captures.get(0).command()).doesNotContain("## Checagens");
    }

    @TestConfiguration
    static class PromptProbeConfig {
        @Bean
        PromptProbe promptProbe() {
            return new PromptProbe();
        }
    }

    /** Adapter rico de mentira: captura o command/env que o executor montou e responde ok. */
    static class PromptProbe implements AgentAdapter {
        record Capture(String command, String promptEnv) {
        }

        final List<Capture> captures = new ArrayList<>();

        @Override
        public String type() {
            return "prompt-probe";
        }

        @Override
        public EngineContainer engineContainer() {
            return EngineContainer.plainShell();
        }

        @Override
        public Result execute(Context context, StepSink steps) {
            captures.add(new Capture(context.command(), context.env().get("ORQ_PROMPT")));
            if (context.command() == null || context.command().isBlank()) {
                return Result.okNoop(context.priorSession());
            }
            steps.emit("RESULT", "ok");
            return Result.ok("sess-1");
        }
    }
}
