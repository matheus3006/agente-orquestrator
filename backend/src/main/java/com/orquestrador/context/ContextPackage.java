package com.orquestrador.context;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.MemoryAudience;

import java.nio.file.Path;
import java.util.List;

/**
 * Resultado em memória da compilação. Prompt e schema nunca atravessam para o manifesto
 * persistido, que contém somente metadados e hashes.
 */
public record ContextPackage(Path workDir, MemoryAudience audience, ExecutionTarget.Type target,
                             List<String> publishedPaths, List<MaterializedArtifact> artifacts,
                             String prompt, String outputSchema, ContextManifest manifest) {

    public ContextPackage {
        publishedPaths = List.copyOf(publishedPaths);
        artifacts = List.copyOf(artifacts);
    }
}
