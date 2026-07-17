package com.orquestrador.context.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAssessmentValidatorTest {

    private final ContextAssessmentValidator validator = new ContextAssessmentValidator();

    @Test
    void aceitaSomenteAchadosEstruturadosQueReferenciamFontesAllowlisted() {
        String source = "a".repeat(64);
        ContextAssessment assessment = new ContextAssessment(ContextAssessment.Status.FINDINGS, List.of(
                new ContextAssessment.Finding(ContextAssessment.Severity.HIGH,
                        ContextAssessment.Category.INSTRUCTION_CONFLICT, List.of(source),
                        ContextAssessment.SuggestedAction.RESOLVE_CONFLICT)));

        assertThat(validator.validateModelOutput(assessment, Set.of(source))).isEqualTo(assessment);
        assertThat(validator.validateModelOutput(ContextAssessment.clean(), Set.of(source)))
                .isEqualTo(ContextAssessment.clean());
    }

    @Test
    void recusaFonteForaDaAllowlistEEstadosQueSomenteOBackendPodeCriar() {
        ContextAssessment unknownSource = new ContextAssessment(ContextAssessment.Status.FINDINGS, List.of(
                new ContextAssessment.Finding(ContextAssessment.Severity.LOW,
                        ContextAssessment.Category.DUPLICATION, List.of("b".repeat(64)),
                        ContextAssessment.SuggestedAction.REMOVE_DUPLICATION)));

        assertThatThrownBy(() -> validator.validateModelOutput(unknownSource, Set.of("a".repeat(64))))
                .isInstanceOf(InvalidContextAssessmentException.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> validator.validateModelOutput(
                ContextAssessment.fallback(ContextAssessment.FallbackReason.TIMEOUT), Set.of()))
                .isInstanceOf(InvalidContextAssessmentException.class)
                .hasMessageContaining("FALLBACK");
    }

    @Test
    void recusaContratoInconsistenteOuGrandeDemais() {
        String source = "a".repeat(64);
        ContextAssessment.Finding finding = new ContextAssessment.Finding(ContextAssessment.Severity.MEDIUM,
                ContextAssessment.Category.MISSING_CONTEXT, List.of(source),
                ContextAssessment.SuggestedAction.ADD_MISSING_CONTEXT);

        assertThatThrownBy(() -> validator.validateModelOutput(
                new ContextAssessment(ContextAssessment.Status.CLEAN, List.of(finding)), Set.of(source)))
                .isInstanceOf(InvalidContextAssessmentException.class);
        assertThatThrownBy(() -> validator.validateModelOutput(
                new ContextAssessment(ContextAssessment.Status.FINDINGS, List.of()), Set.of(source)))
                .isInstanceOf(InvalidContextAssessmentException.class);
        assertThatThrownBy(() -> validator.validateModelOutput(
                new ContextAssessment(ContextAssessment.Status.FINDINGS, java.util.Collections.nCopies(21, finding)),
                Set.of(source)))
                .isInstanceOf(InvalidContextAssessmentException.class)
                .hasMessageContaining("20");
    }
}
