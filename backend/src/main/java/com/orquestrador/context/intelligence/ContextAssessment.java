package com.orquestrador.context.intelligence;

import java.util.List;

/**
 * Diagnóstico derivado e estritamente estruturado. Não há campo de texto livre onde o modelo
 * possa ecoar corpo, prompt, segredo ou PII.
 */
public record ContextAssessment(Status status, List<Finding> findings) {

    public ContextAssessment {
        findings = findings == null ? null : List.copyOf(findings);
    }

    public static ContextAssessment clean() {
        return new ContextAssessment(Status.CLEAN, List.of());
    }

    public static ContextAssessment fallback(FallbackReason ignored) {
        return new ContextAssessment(Status.FALLBACK, List.of());
    }

    public enum Status {
        CLEAN,
        FINDINGS,
        FALLBACK
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Category {
        CONTRADICTION,
        DUPLICATION,
        MISSING_CONTEXT,
        INSTRUCTION_CONFLICT,
        OVERSIZED_SOURCE
    }

    public enum SuggestedAction {
        REVIEW_SOURCE,
        REMOVE_DUPLICATION,
        RESOLVE_CONFLICT,
        ADD_MISSING_CONTEXT,
        SPLIT_SOURCE
    }

    public enum FallbackReason {
        TIMEOUT,
        RATE_LIMITED,
        PROVIDER_5XX,
        INVALID_RESPONSE,
        UNAVAILABLE,
        EXCEPTION,
        QUEUE_FULL
    }

    public record Finding(Severity severity, Category category, List<String> sourceHashes,
                          SuggestedAction suggestedAction) {

        public Finding {
            sourceHashes = sourceHashes == null ? null : List.copyOf(sourceHashes);
        }
    }
}
