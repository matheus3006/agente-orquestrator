package com.orquestrador.context.intelligence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistência estreita de resultados estruturados e metadados, sem qualquer campo de conteúdo. */
public interface ContextAssessmentStore {

    Optional<ContextAssessment> findCached(CacheKey key);

    void save(Observation observation);

    record CacheKey(UUID companyId, String sourceHash, String promptVersion, String provider, String model) {
    }

    record Observation(UUID id, UUID runId, UUID companyId, String mode, String provider, String model,
                       String promptVersion, String sourceHash, ContextAssessment assessment, boolean cacheHit,
                       long latencyMs, Integer inputTokens, Integer outputTokens,
                       ContextAssessment.FallbackReason fallbackReason, Instant createdAt) {
    }
}
