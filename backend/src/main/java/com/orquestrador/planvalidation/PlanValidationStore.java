package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O store do registro do validador (V33) — telemetria E fonte do push, o duplo papel decidido no
 * grill. Módulo profundo: quem escreve não sabe de query, e quem lê ("o veredito mais recente desta
 * issue") não sabe de linha nem de created_at.
 */
@Service
public class PlanValidationStore {

    private final PlanValidationRepository repository;

    public PlanValidationStore(PlanValidationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Scope scope, List<RoleTemplateService.Check> checks,
                       List<PlanCheckVerdict> verdicts, String model) {
        Map<String, String> textoPorId = checks.stream().collect(Collectors.toMap(
                RoleTemplateService.Check::id, RoleTemplateService.Check::text, (a, b) -> a));
        Instant agora = Instant.now();
        List<StoredPlanValidation> linhas = new ArrayList<>();
        for (PlanCheckVerdict verdict : verdicts) {
            linhas.add(new StoredPlanValidation(UUID.randomUUID(), scope.companyId(), scope.runId(),
                    scope.agentId(), scope.issueId(), verdict.checkId(),
                    textoPorId.getOrDefault(verdict.checkId(), verdict.checkId()),
                    verdict.presence(), verdict.evidence(), model, agora));
        }
        repository.saveAll(linhas);
    }

    /**
     * O veredito MAIS RECENTE da issue — escopado ao RUN inteiro, não à linha mais nova. Sem esse
     * escopo, um run que conferiu 2 checagens misturaria a 3ª do run anterior e o checklist do
     * arquiteto viraria uma colcha de retalhos de execuções diferentes.
     */
    @Transactional(readOnly = true)
    public List<PlanCheckVerdict> latestForIssue(UUID issueId) {
        return repository.findFirstByIssueIdOrderByCreatedAtDesc(issueId)
                .map(StoredPlanValidation::getRunId)
                .map(repository::findByRunIdOrderByCreatedAtAsc)
                .orElseGet(List::of)
                .stream()
                .map(toVerdict())
                .toList();
    }

    private static Function<StoredPlanValidation, PlanCheckVerdict> toVerdict() {
        return linha -> new PlanCheckVerdict(linha.getCheckId(), linha.getPresence(),
                linha.getEvidence() == null ? "" : linha.getEvidence());
    }

    /** De onde veio o veredito — o endereço que amarra a telemetria ao run/issue/agente. */
    public record Scope(UUID companyId, UUID runId, UUID agentId, UUID issueId) {
    }
}
