package com.orquestrador.context.intelligence;

import com.orquestrador.context.ContextManifest;
import com.orquestrador.context.ContextPackage;
import com.orquestrador.context.ContextPolicy;
import com.orquestrador.context.ContextSource;
import com.orquestrador.context.MaterializedArtifact;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.MemoryAudience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiContextIntelligenceTest {

    @TempDir
    Path tempDir;

    @Test
    void shadowEnviaSomenteFontesCanonicasEJamaisMutaOContextoDoRun() throws Exception {
        ContextPackage context = contextPackage(UUID.randomUUID(), "corpo-canonico");
        byte[] originalFile = Files.readAllBytes(context.workDir().resolve(context.artifacts().getFirst().relativePath()));
        AtomicReference<Prompt> received = new AtomicReference<>();
        FakeChatModel model = new FakeChatModel(prompt -> {
            received.set(prompt);
            return response("{\"status\":\"CLEAN\",\"findings\":[]}");
        });
        RecordingStore store = new RecordingStore(1);

        try (SpringAiContextIntelligence intelligence = intelligence(model, store, Duration.ofSeconds(1))) {
            intelligence.inspect(ContextIntelligence.View.from(context));
            assertThat(store.await()).isTrue();
        }

        assertThat(received.get().getContents())
                .contains("corpo-canonico", context.artifacts().getFirst().sha256())
                .doesNotContain("prompt-vivo-secreto", "schema-vivo-secreto");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(store.observations).singleElement().satisfies(observation -> {
            assertThat(observation.assessment()).isEqualTo(ContextAssessment.clean());
            assertThat(observation.inputTokens()).isEqualTo(12);
            assertThat(observation.outputTokens()).isEqualTo(3);
            assertThat(observation.fallbackReason()).isNull();
        });
        assertThat(context.prompt()).isEqualTo("prompt-vivo-secreto");
        assertThat(context.outputSchema()).isEqualTo("schema-vivo-secreto");
        assertThat(Files.readAllBytes(context.workDir().resolve(context.artifacts().getFirst().relativePath())))
                .isEqualTo(originalFile);
    }

    @Test
    void cacheUsaHashVersaoProviderEModeloSemSegundaChamada() throws Exception {
        UUID companyId = UUID.randomUUID();
        ContextPackage first = contextPackage(UUID.randomUUID(), companyId, "mesmo-corpo");
        ContextPackage second = contextPackage(UUID.randomUUID(), companyId, "mesmo-corpo");
        FakeChatModel model = new FakeChatModel(ignored -> response("{\"status\":\"CLEAN\",\"findings\":[]}"));
        RecordingStore store = new RecordingStore(2);

        try (SpringAiContextIntelligence intelligence = intelligence(model, store, Duration.ofSeconds(1))) {
            intelligence.inspect(ContextIntelligence.View.from(first));
            assertThat(store.awaitCount(1)).isTrue();
            intelligence.inspect(ContextIntelligence.View.from(second));
            assertThat(store.await()).isTrue();
        }

        assertThat(model.calls()).isEqualTo(1);
        assertThat(store.observations).hasSize(2);
        assertThat(store.observations.get(0).cacheHit()).isFalse();
        assertThat(store.observations.get(1).cacheHit()).isTrue();
    }

    @Test
    void timeoutNaoBloqueiaNemFalhaOChamador() throws Exception {
        FakeChatModel model = new FakeChatModel(ignored -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return response("{\"status\":\"CLEAN\",\"findings\":[]}");
        });
        RecordingStore store = new RecordingStore(1);

        long started = System.nanoTime();
        try (SpringAiContextIntelligence intelligence = intelligence(model, store, Duration.ofMillis(50))) {
            intelligence.inspect(ContextIntelligence.View.from(contextPackage(UUID.randomUUID(), "corpo")));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
            assertThat(store.await()).isTrue();
        }

        assertThat(store.observations).singleElement().satisfies(observation -> {
            assertThat(observation.assessment().status()).isEqualTo(ContextAssessment.Status.FALLBACK);
            assertThat(observation.fallbackReason()).isEqualTo(ContextAssessment.FallbackReason.TIMEOUT);
        });
    }

    @Test
    void converteErrosDoProviderERespostaInvalidaEmFallbackNaoFatal() throws Exception {
        assertFallback(ignored -> {
            throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate", HttpHeaders.EMPTY,
                    new byte[0], StandardCharsets.UTF_8);
        }, ContextAssessment.FallbackReason.RATE_LIMITED);
        assertFallback(ignored -> {
            throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "provider", HttpHeaders.EMPTY,
                    new byte[0], StandardCharsets.UTF_8);
        }, ContextAssessment.FallbackReason.PROVIDER_5XX);
        assertFallback(ignored -> {
            throw new ResourceAccessException("offline", new ConnectException("refused"));
        }, ContextAssessment.FallbackReason.UNAVAILABLE);
        assertFallback(ignored -> response("nao-e-json"), ContextAssessment.FallbackReason.INVALID_RESPONSE);
    }

    @Test
    void fonteAlteradaDepoisDaCompilacaoNaoAtravessaAAllowlist() throws Exception {
        ContextPackage context = contextPackage(UUID.randomUUID(), "corpo-original");
        Files.writeString(context.workDir().resolve(context.artifacts().getFirst().relativePath()), "corpo-adulterado");
        AtomicReference<Prompt> received = new AtomicReference<>();
        FakeChatModel model = new FakeChatModel(prompt -> {
            received.set(prompt);
            return response("{\"status\":\"CLEAN\",\"findings\":[]}");
        });
        RecordingStore store = new RecordingStore(1);

        try (SpringAiContextIntelligence intelligence = intelligence(model, store, Duration.ofSeconds(1))) {
            intelligence.inspect(ContextIntelligence.View.from(context));
            assertThat(store.await()).isTrue();
        }

        assertThat(received.get().getContents()).doesNotContain("corpo-adulterado", "corpo-original");
    }

    @Test
    void workerEQueueLimitadosRejeitamExcessoComFallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeChatModel model = new FakeChatModel(ignored -> {
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return response("{\"status\":\"CLEAN\",\"findings\":[]}");
        });
        RecordingStore store = new RecordingStore(1);
        ContextIntelligenceProperties properties = properties(Duration.ofSeconds(2));
        properties.setQueueCapacity(1);

        try (SpringAiContextIntelligence intelligence = new SpringAiContextIntelligence(
                model, properties, store, new ContextAssessmentValidator())) {
            intelligence.inspect(ContextIntelligence.View.from(contextPackage(UUID.randomUUID(), "primeiro")));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            intelligence.inspect(ContextIntelligence.View.from(contextPackage(UUID.randomUUID(), "segundo")));
            intelligence.inspect(ContextIntelligence.View.from(contextPackage(UUID.randomUUID(), "excesso")));

            assertThat(store.await()).isTrue();
            assertThat(store.observations).anySatisfy(observation ->
                    assertThat(observation.fallbackReason()).isEqualTo(ContextAssessment.FallbackReason.QUEUE_FULL));
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    private void assertFallback(Function<Prompt, ChatResponse> behavior,
                                ContextAssessment.FallbackReason expected) throws Exception {
        FakeChatModel model = new FakeChatModel(behavior);
        RecordingStore store = new RecordingStore(1);
        try (SpringAiContextIntelligence intelligence = intelligence(model, store, Duration.ofSeconds(1))) {
            intelligence.inspect(ContextIntelligence.View.from(contextPackage(UUID.randomUUID(), "corpo")));
            assertThat(store.await()).isTrue();
        }
        assertThat(store.observations).singleElement()
                .extracting(ContextAssessmentStore.Observation::fallbackReason)
                .isEqualTo(expected);
    }

    private SpringAiContextIntelligence intelligence(ChatModel model, RecordingStore store, Duration timeout) {
        return new SpringAiContextIntelligence(model, properties(timeout), store,
                new ContextAssessmentValidator());
    }

    private ContextIntelligenceProperties properties(Duration timeout) {
        ContextIntelligenceProperties properties = new ContextIntelligenceProperties();
        properties.setMode(ContextIntelligenceProperties.Mode.SHADOW);
        properties.setTimeout(timeout);
        properties.setMaxInputBytes(65_536);
        properties.setPromptVersion("context-lint-v1");
        properties.setProvider("ollama");
        properties.setModel("fake-model");
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(8);
        return properties;
    }

    private ContextPackage contextPackage(UUID runId, String body) throws Exception {
        return contextPackage(runId, UUID.randomUUID(), body);
    }

    private ContextPackage contextPackage(UUID runId, UUID companyId, String body) throws Exception {
        Path workDir = tempDir.resolve(runId.toString());
        String relativePath = ".agents/skills/contexto.md";
        Path file = workDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        String sourceHash = sha256(bytes);
        MaterializedArtifact artifact = new MaterializedArtifact(relativePath, sourceHash, bytes.length);
        ContextSource source = new ContextSource("MATERIALIZED_ARTIFACT", "workspace:" + "b".repeat(64),
                "materialization-v1", sourceHash, bytes.length, ContextPolicy.Classification.CANONICAL,
                ContextSource.Inclusion.INCLUDED, "RUN_CONTEXT");
        ContextManifest manifest = new ContextManifest(runId, companyId, UUID.randomUUID(), null,
                MemoryAudience.EXECUTOR, ExecutionTarget.Type.LOCAL, "CODEX", 1, Instant.now(),
                ContextManifest.Status.COMPILED, "f".repeat(64), bytes.length, 4, List.of(source));
        return new ContextPackage(workDir, MemoryAudience.EXECUTOR, ExecutionTarget.Type.LOCAL,
                List.of(relativePath), List.of(artifact), "prompt-vivo-secreto", "schema-vivo-secreto", manifest);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ChatResponse response(String content) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(12, 3))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
    }

    private static final class FakeChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final Function<Prompt, ChatResponse> behavior;

        private FakeChatModel(Function<Prompt, ChatResponse> behavior) {
            this.behavior = behavior;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return behavior.apply(prompt);
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class RecordingStore implements ContextAssessmentStore {
        private final CountDownLatch saved;
        private final List<Observation> observations = new ArrayList<>();
        private ContextAssessment cached;

        private RecordingStore(int expected) {
            this.saved = new CountDownLatch(expected);
        }

        @Override
        public synchronized Optional<ContextAssessment> findCached(CacheKey key) {
            return Optional.ofNullable(cached);
        }

        @Override
        public synchronized void save(Observation observation) {
            observations.add(observation);
            if (observation.assessment().status() != ContextAssessment.Status.FALLBACK) {
                cached = observation.assessment();
            }
            saved.countDown();
        }

        private boolean await() throws InterruptedException {
            return saved.await(3, TimeUnit.SECONDS);
        }

        private boolean awaitCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                synchronized (this) {
                    if (observations.size() >= expected) {
                        return true;
                    }
                }
                Thread.sleep(5);
            }
            return false;
        }
    }
}
