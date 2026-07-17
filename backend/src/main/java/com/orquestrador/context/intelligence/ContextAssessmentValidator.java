package com.orquestrador.context.intelligence;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Validação final deny-by-default; o schema do provider nunca é tratado como autoridade. */
@Component
public class ContextAssessmentValidator {

    private static final int MAX_FINDINGS = 20;
    private static final int MAX_SOURCES_PER_FINDING = 8;

    public ContextAssessment validateModelOutput(ContextAssessment candidate, Set<String> allowedSourceHashes) {
        if (candidate == null || candidate.status() == null || candidate.findings() == null) {
            throw invalid("resposta incompleta");
        }
        if (candidate.status() == ContextAssessment.Status.FALLBACK) {
            throw invalid("FALLBACK é estado exclusivo do backend");
        }
        if (candidate.status() == ContextAssessment.Status.CLEAN && !candidate.findings().isEmpty()) {
            throw invalid("CLEAN não aceita achados");
        }
        if (candidate.status() == ContextAssessment.Status.FINDINGS && candidate.findings().isEmpty()) {
            throw invalid("FINDINGS exige ao menos um achado");
        }
        if (candidate.findings().size() > MAX_FINDINGS) {
            throw invalid("máximo de 20 achados excedido");
        }
        for (ContextAssessment.Finding finding : candidate.findings()) {
            validateFinding(finding, allowedSourceHashes);
        }
        return candidate;
    }

    private static void validateFinding(ContextAssessment.Finding finding, Set<String> allowedSourceHashes) {
        if (finding == null || finding.severity() == null || finding.category() == null
                || finding.suggestedAction() == null || finding.sourceHashes() == null
                || finding.sourceHashes().isEmpty()) {
            throw invalid("achado incompleto");
        }
        if (finding.sourceHashes().size() > MAX_SOURCES_PER_FINDING) {
            throw invalid("achado referencia fontes demais");
        }
        Set<String> unique = new HashSet<>(finding.sourceHashes());
        if (unique.size() != finding.sourceHashes().size()) {
            throw invalid("achado repete fonte");
        }
        if (!allowedSourceHashes.containsAll(unique)) {
            throw invalid("achado referencia fonte fora da allowlist");
        }
    }

    private static InvalidContextAssessmentException invalid(String detail) {
        return new InvalidContextAssessmentException("ContextAssessment inválido: " + detail);
    }
}
