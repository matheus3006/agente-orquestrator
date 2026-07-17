package com.orquestrador.context;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.MemoryAudience;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Manifesto imutável e sem corpos do pacote que um Run recebeu. */
public record ContextManifest(UUID runId, UUID companyId, UUID agentId, UUID issueId,
                              MemoryAudience audience, ExecutionTarget.Type target, String convention,
                              int schemaVersion, Instant compiledAt, Status status, String finalHash,
                              long totalBytes, long estimatedTokens, List<ContextSource> sources) {

    public ContextManifest {
        sources = List.copyOf(sources);
    }

    public enum Status {
        COMPILED
    }
}
