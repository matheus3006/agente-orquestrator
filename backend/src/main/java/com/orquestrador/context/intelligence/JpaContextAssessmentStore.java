package com.orquestrador.context.intelligence;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Transações curtas e isoladas; a chamada ao modelo acontece fora deste adaptador. */
@Component
public class JpaContextAssessmentStore implements ContextAssessmentStore {

    private static final List<ContextAssessment.Status> CACHEABLE =
            List.of(ContextAssessment.Status.CLEAN, ContextAssessment.Status.FINDINGS);

    private final ContextAssessmentRepository assessments;
    private final ObjectMapper json;

    public JpaContextAssessmentStore(ContextAssessmentRepository assessments, ObjectMapper json) {
        this.assessments = assessments;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextAssessment> findCached(CacheKey key) {
        return assessments
                .findFirstByCompanyIdAndSourceHashAndPromptVersionAndProviderAndModelAndStatusInOrderByCreatedAtDesc(
                        key.companyId(), key.sourceHash(), key.promptVersion(), key.provider(), key.model(), CACHEABLE)
                .map(this::toAssessment);
    }

    @Override
    @Transactional
    public void save(Observation observation) {
        try {
            String findings = json.writeValueAsString(observation.assessment().findings());
            assessments.saveAndFlush(new StoredContextAssessment(observation, findings));
        } catch (JacksonException exception) {
            throw new IllegalStateException("não foi possível serializar o ContextAssessment", exception);
        }
    }

    private ContextAssessment toAssessment(StoredContextAssessment stored) {
        try {
            ContextAssessment.Finding[] findings =
                    json.readValue(stored.getFindingsJson(), ContextAssessment.Finding[].class);
            return new ContextAssessment(stored.getStatus(), Arrays.asList(findings));
        } catch (JacksonException exception) {
            throw new IllegalStateException("ContextAssessment persistido é inválido", exception);
        }
    }
}
