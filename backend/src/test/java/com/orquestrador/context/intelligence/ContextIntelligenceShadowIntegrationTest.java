package com.orquestrador.context.intelligence;

import com.orquestrador.context.ContextManifest;
import com.orquestrador.context.ContextPackage;
import com.orquestrador.context.ContextPolicy;
import com.orquestrador.context.ContextSource;
import com.orquestrador.context.MaterializedArtifact;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.MemoryAudience;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "orquestrador.context.intelligence.mode=shadow",
        "orquestrador.context.intelligence.timeout=1s",
        "orquestrador.context.intelligence.model=fake-model"
})
@ActiveProfiles("test")
@Import(ContextIntelligenceShadowIntegrationTest.FakeConfiguration.class)
class ContextIntelligenceShadowIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private ContextIntelligence intelligence;

    @Autowired
    private FakeChatModel model;

    @Autowired
    private RecordingStore store;

    @Autowired
    private ApplicationContext application;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;

    @BeforeEach
    void resetFakes() {
        model.reset();
        store.reset();
    }

    @Test
    void shadowUsaChatModelFakeForaDeTransacaoESemRecursosGenericos() throws Exception {
        assertThat(intelligence).isInstanceOf(SpringAiContextIntelligence.class);
        assertThat(application.getBeansOfType(ChatMemory.class)).isEmpty();
        assertThat(application.getBeansOfType(ToolCallback.class)).isEmpty();
        assertThat(application.getBeansOfType(ChatClient.Builder.class)).isEmpty();

        ContextPackage contextPackage = contextPackage();
        intelligence.inspect(ContextIntelligence.View.from(contextPackage));

        assertThat(store.saved.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.observation.assessment()).isEqualTo(ContextAssessment.clean());
        assertThat(model.transactionActive).isFalse();
        assertThat(model.prompt.getContents())
                .contains("conteudo-canonico")
                .doesNotContain("prompt-do-run", "schema-do-run");
    }

    @Test
    void modeloBloqueadoNaoAlteraODesfechoDoRun() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        model.block();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(model.called.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(store.observation).isNull();

        model.release();
        assertThat(store.saved.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.observation.assessment()).isEqualTo(ContextAssessment.clean());
    }

    private ContextPackage contextPackage() throws Exception {
        UUID runId = UUID.randomUUID();
        byte[] body = "conteudo-canonico".getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String relativePath = ".agents/skills/contexto.md";
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        ContextSource source = new ContextSource("MATERIALIZED_ARTIFACT", "workspace:" + "a".repeat(64),
                "materialization-v1", hash, body.length, ContextPolicy.Classification.CANONICAL,
                ContextSource.Inclusion.INCLUDED, "RUN_CONTEXT");
        ContextManifest manifest = new ContextManifest(runId, UUID.randomUUID(), UUID.randomUUID(), null,
                MemoryAudience.EXECUTOR, ExecutionTarget.Type.LOCAL, "CODEX", 1, Instant.now(),
                ContextManifest.Status.COMPILED, "f".repeat(64), body.length, 5, List.of(source));
        return new ContextPackage(tempDir, MemoryAudience.EXECUTOR, ExecutionTarget.Type.LOCAL,
                List.of(relativePath), List.of(new MaterializedArtifact(relativePath, hash, body.length)),
                "prompt-do-run", "schema-do-run", manifest);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeConfiguration {

        @Bean
        @Primary
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        @Primary
        RecordingStore recordingContextAssessmentStore() {
            return new RecordingStore();
        }
    }

    static final class FakeChatModel implements ChatModel {
        private volatile Prompt prompt;
        private volatile boolean transactionActive;
        private volatile CountDownLatch called = new CountDownLatch(1);
        private volatile CountDownLatch gate = new CountDownLatch(0);

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            this.transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            called.countDown();
            try {
                gate.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"status\":\"CLEAN\",\"findings\":[]}"))));
        }

        void reset() {
            prompt = null;
            transactionActive = false;
            called = new CountDownLatch(1);
            gate = new CountDownLatch(0);
        }

        void block() {
            gate = new CountDownLatch(1);
        }

        void release() {
            gate.countDown();
        }
    }

    static final class RecordingStore implements ContextAssessmentStore {
        private volatile CountDownLatch saved = new CountDownLatch(1);
        private volatile Observation observation;

        @Override
        public Optional<ContextAssessment> findCached(CacheKey key) {
            return Optional.empty();
        }

        @Override
        public void save(Observation observation) {
            this.observation = observation;
            saved.countDown();
        }

        void reset() {
            observation = null;
            saved = new CountDownLatch(1);
        }
    }
}
