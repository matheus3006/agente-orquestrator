package com.orquestrador.context.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaContextAssessmentStoreTest {

    @Mock
    private ContextAssessmentRepository repository;

    @Test
    void persisteSomenteAchadosEstruturadosEMetadadosEReconstruiCache() {
        JpaContextAssessmentStore store = new JpaContextAssessmentStore(repository, new ObjectMapper());
        UUID companyId = UUID.randomUUID();
        String sourceHash = "a".repeat(64);
        ContextAssessment assessment = new ContextAssessment(ContextAssessment.Status.FINDINGS, List.of(
                new ContextAssessment.Finding(ContextAssessment.Severity.MEDIUM,
                        ContextAssessment.Category.DUPLICATION, List.of(sourceHash),
                        ContextAssessment.SuggestedAction.REMOVE_DUPLICATION)));
        ContextAssessmentStore.Observation observation = new ContextAssessmentStore.Observation(
                UUID.randomUUID(), UUID.randomUUID(), companyId, "SHADOW", "ollama", "fake-model",
                "context-lint-v1", sourceHash, assessment, false, 19, 7, 2, null, Instant.now());

        store.save(observation);

        ArgumentCaptor<StoredContextAssessment> captured = ArgumentCaptor.forClass(StoredContextAssessment.class);
        verify(repository).saveAndFlush(captured.capture());
        StoredContextAssessment stored = captured.getValue();
        assertThat(stored.getFindingsJson())
                .contains("DUPLICATION", sourceHash)
                .doesNotContain("prompt", "response", "body", "secret");
        assertThat(stored.getStatus()).isEqualTo(ContextAssessment.Status.FINDINGS);

        when(repository.findFirstByCompanyIdAndSourceHashAndPromptVersionAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
                companyId, sourceHash, "context-lint-v1", "ollama", "fake-model", List.of(
                        ContextAssessment.Status.CLEAN, ContextAssessment.Status.FINDINGS)))
                .thenReturn(Optional.of(stored));

        assertThat(store.findCached(new ContextAssessmentStore.CacheKey(companyId, sourceHash,
                "context-lint-v1", "ollama", "fake-model"))).contains(assessment);
    }
}
