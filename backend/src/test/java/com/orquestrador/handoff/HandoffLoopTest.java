package com.orquestrador.handoff;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.EngineContainer;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.engine.EngineCatalog;
import com.orquestrador.org.engine.EngineCatalogRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 ponta-a-ponta pelo pipeline REAL do executor: um turno do CEO que cruza o limiar da janela (com
 * o bilhete já escrito) enfileira UMA continuação; abaixo do limiar, nenhuma. O probe emite 0 tokens
 * numa continuação (ela não re-cruza) — isola o disparo do original e evita cascata no dreno.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(HandoffLoopTest.ProbeConfig.class)
class HandoffLoopTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private com.orquestrador.conversation.ConversationTurnRepository turns;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private EngineCatalogRepository engines;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private void contrataCeoProbe(UUID companyId, String model, String effort) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("handoff-probe", null, "LOCAL");
        ceo.configureEngine(null, model, effort);
        agents.save(ceo);
    }

    /** Esvazia a fila; a continuação NÃO re-cruza (probe emite 0 nela), então o loop termina. */
    private void drena() {
        for (int i = 0; i < 12 && dispatcher.dispatchOnce() > 0; i++) {
            // no-op
        }
    }

    private long continuacoes(UUID companyId, UUID conversationId) {
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversationId, companyId).stream()
                .filter(turno -> ContinuationSentinel.isContinuation(turno.getOwnerMessage())).count();
    }

    @Test
    void turnoQueCruzaOLimiarComBilheteEnfileiraUmaUnicaContinuacao() {
        UUID companyId = novaCompany();
        // Janela pequena → o run (1000 tokens de entrada) cruza os 55%.
        engines.save(new EngineCatalog(UUID.randomUUID(), "handoff-probe", "cross-m", "cross-e",
                100, "loop-cross", true));
        contrataCeoProbe(companyId, "cross-m", "cross-e");
        Conversation conv = conversations.open(companyId, "Fundo");
        conversations.postMessage(companyId, conv.getId(), "vai fundo", ActorType.BOARD, UUID.randomUUID());

        drena();

        assertThat(continuacoes(companyId, conv.getId())).isEqualTo(1);
    }

    @Test
    void turnoAbaixoDoLimiarNaoEnfileiraContinuacao() {
        UUID companyId = novaCompany();
        // Janela enorme → 1000 tokens não chega perto dos 55%.
        engines.save(new EngineCatalog(UUID.randomUUID(), "handoff-probe", "big-m", "big-e",
                1_000_000, "loop-below", true));
        contrataCeoProbe(companyId, "big-m", "big-e");
        Conversation conv = conversations.open(companyId, "Raso");
        conversations.postMessage(companyId, conv.getId(), "oi", ActorType.BOARD, UUID.randomUUID());

        drena();

        assertThat(continuacoes(companyId, conv.getId())).isZero();
    }

    @Test
    void redeResumeCirurgicoQueFalhaLogaOMotivoESegueFailOpen() {
        UUID companyId = novaCompany();
        engines.save(new EngineCatalog(UUID.randomUUID(), "handoff-fail", "fail-m", "fail-e",
                100, "loop-fail", true));
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("handoff-fail", null, "LOCAL");
        ceo.configureEngine(null, "fail-m", "fail-e");
        agents.save(ceo);
        Conversation conv = conversations.open(companyId, "RedeFalha");
        conversations.postMessage(companyId, conv.getId(), "vai fundo", ActorType.BOARD, UUID.randomUUID());

        ListAppender<ILoggingEvent> warns = new ListAppender<>();
        warns.start();
        ch.qos.logback.classic.Logger loopLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ContextHandoffLoop.class);
        loopLogger.addAppender(warns);
        try {
            drena();
        } finally {
            loopLogger.detachAppender(warns);
        }

        // Anti-silêncio (laudo motor-nativo R6): o adapter devolve Result.fail (não lança) — o motivo
        // da falha do resume TEM que chegar ao log, senão o único rastro é "bilhete ausente" genérico.
        assertThat(warns.list)
                .filteredOn(evento -> evento.getLevel() == Level.WARN)
                .anySatisfy(evento -> assertThat(evento.getFormattedMessage())
                        .contains("resume-cirúrgico falhou")
                        .contains("exit 2"));
        // Fail-open intacto (D8/D9): sem bilhete ⟹ sem continuação; o turno liquidado não reabre.
        assertThat(continuacoes(companyId, conv.getId())).isZero();
    }

    @Test
    void semBilheteARedeResumeCirurgicoOGravaEEntaoContinua() {
        UUID companyId = novaCompany();
        engines.save(new EngineCatalog(UUID.randomUUID(), "handoff-net", "net-m", "net-e",
                100, "loop-net", true));
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("handoff-net", null, "LOCAL");
        ceo.configureEngine(null, "net-m", "net-e");
        agents.save(ceo);
        Conversation conv = conversations.open(companyId, "SemBilhete");
        conversations.postMessage(companyId, conv.getId(), "vai fundo", ActorType.BOARD, UUID.randomUUID());

        drena();

        // O 1º run cruzou SEM gravar o bilhete → a rede reabriu a sessão (--resume) e o gravou → nasceu
        // 1 continuação (se a rede não tivesse rodado, o bilhete faltaria e não haveria continuação).
        assertThat(continuacoes(companyId, conv.getId())).isEqualTo(1);
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        AgentAdapter handoffProbeAdapter() {
            return new AgentAdapter() {
                private final Pattern notePath = Pattern.compile("\\.orq/handoff/[0-9a-fA-F\\-]+\\.md");

                @Override
                public String type() {
                    return "handoff-probe";
                }

                @Override
                public EngineContainer engineContainer() {
                    return EngineContainer.plainShell();
                }

                @Override
                public Result execute(Context context, StepSink steps) {
                    String prompt = context.env().getOrDefault("ORQ_PROMPT", "");
                    // Self-distill feliz: grava o bilhete no caminho que o prompt indicou (como o real).
                    Matcher matcher = notePath.matcher(prompt);
                    if (matcher.find()) {
                        try {
                            Path note = context.workDir().resolve(matcher.group());
                            Files.createDirectories(note.getParent());
                            Files.writeString(note, "## Onde estou\nbilhete de teste\n");
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }
                    steps.emit("RESULT", "ok");
                    // Uma continuação NÃO re-cruza (emite 0) → só o turno original dispara o handoff.
                    boolean continuacao = prompt.contains("[continuação");
                    int tokensInput = continuacao ? 0 : 1000;
                    return Result.ok("sess-probe", List.of(new CostEmission("anthropic", "m", tokensInput, 0)));
                }
            };
        }

        /** Cruza sem bilhete E a rede FALHA como o codex real falharia (Result.fail, não exceção). */
        @Bean
        AgentAdapter handoffFailAdapter() {
            return new AgentAdapter() {
                @Override
                public String type() {
                    return "handoff-fail";
                }

                @Override
                public EngineContainer engineContainer() {
                    return EngineContainer.plainShell();
                }

                @Override
                public Result execute(Context context, StepSink steps) {
                    if (context.priorSession() != null) {
                        // O shape REAL da falha: o adapter traduz exit≠0 em Result.fail (CodexAdapter:65-67).
                        return Result.fail("codex terminou com exit 2: error: unexpected argument"
                                + " '--resume' found");
                    }
                    steps.emit("RESULT", "ok");
                    String prompt = context.env().getOrDefault("ORQ_PROMPT", "");
                    int tokensInput = prompt.contains("[continuação") ? 0 : 1000;
                    return Result.ok("sess-fail-x", List.of(new CostEmission("openai", "m", tokensInput, 0)));
                }
            };
        }

        /** Cruza o limiar mas NÃO grava o bilhete no run normal — força a rede resume-cirúrgico (M9). */
        @Bean
        AgentAdapter handoffNetAdapter() {
            return new AgentAdapter() {
                private final Pattern notePath = Pattern.compile("\\.orq/handoff/[0-9a-fA-F\\-]+\\.md");

                @Override
                public String type() {
                    return "handoff-net";
                }

                @Override
                public EngineContainer engineContainer() {
                    return EngineContainer.plainShell();
                }

                @Override
                public Result execute(Context context, StepSink steps) {
                    if (context.priorSession() != null) {
                        // Resume cirúrgico (--resume): SÓ AGORA grava o bilhete (o self-distill falhou).
                        Matcher matcher = notePath.matcher(context.command() == null ? "" : context.command());
                        if (matcher.find()) {
                            try {
                                Path note = context.workDir().resolve(matcher.group());
                                Files.createDirectories(note.getParent());
                                Files.writeString(note, "## Onde estou\nbilhete pela rede resume\n");
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        }
                        return Result.ok(null); // sessão do resume DESCARTADA (não persistida)
                    }
                    steps.emit("RESULT", "ok");
                    // Turno original: cruza (1000) SEM bilhete → força a rede. Continuação: 0 (não re-cruza).
                    String prompt = context.env().getOrDefault("ORQ_PROMPT", "");
                    int tokensInput = prompt.contains("[continuação") ? 0 : 1000;
                    return Result.ok("sess-net-x", List.of(new CostEmission("anthropic", "m", tokensInput, 0)));
                }
            };
        }
    }
}
