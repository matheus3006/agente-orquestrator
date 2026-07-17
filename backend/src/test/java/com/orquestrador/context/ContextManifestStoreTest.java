package com.orquestrador.context;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.MemoryAudience;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ContextManifestStoreTest {

    @Autowired
    private ContextManifestStore store;
    @Autowired
    private RunContextManifestRepository manifests;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void persisteUmaVezPorRunSemCorpoPromptSegredoOuPii() {
        UUID companyId = companies.create("Context Co", "ctx-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = agents.save(new Agent(UUID.randomUUID(), companyId, "Executor", "worker", null));
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agent.getId(), null,
                RunStatus.RUNNING)).getId();
        ContextSource source = new ContextSource("MATERIALIZED_ARTIFACT", "workspace:opaque-address",
                "materialization-v1", "a".repeat(64), 42, ContextPolicy.Classification.CANONICAL,
                ContextSource.Inclusion.INCLUDED, "RUN_CONTEXT");
        ContextManifest manifest = new ContextManifest(runId, companyId, agent.getId(), null,
                MemoryAudience.EXECUTOR, ExecutionTarget.Type.LOCAL, "CLAUDE", 1, Instant.now(),
                ContextManifest.Status.COMPILED, "b".repeat(64), 42, 11, List.of(source));

        store.save(manifest);

        StoredContextManifest stored = manifests.findById(runId).orElseThrow();
        assertThat(stored.getSourcesJson())
                .contains("opaque-address", "a".repeat(64), "CANONICAL")
                .doesNotContain("super-secret", "prompt vivo", "skill-body", "Context Co", "Executor");
        assertThat(stored.getFinalHash()).isEqualTo("b".repeat(64));
        assertThat(stored.getTotalBytes()).isEqualTo(42);
        assertThat(stored.getEstimatedTokens()).isEqualTo(11);

        assertThatThrownBy(() -> store.save(manifest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imutável");
    }
}
