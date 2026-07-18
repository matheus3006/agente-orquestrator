package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanValidationStoreTest {

    @Mock
    private PlanValidationRepository repository;

    private final UUID companyId = UUID.randomUUID();
    private final UUID issueId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    @Test
    void gravaUmaLinhaPorChecagemComOTextoVigenteDoMolde() {
        PlanValidationStore store = new PlanValidationStore(repository);
        UUID runId = UUID.randomUUID();

        store.record(new PlanValidationStore.Scope(companyId, runId, agentId, issueId),
                List.of(new RoleTemplateService.Check("test", "O plano tem seção de teste?")),
                List.of(new PlanCheckVerdict("test", Presence.ABSENT, "não há seção de testes")),
                "qwen3:1.7b");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoredPlanValidation>> captured = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captured.capture());
        StoredPlanValidation linha = captured.getValue().get(0);
        assertThat(linha.getCheckId()).isEqualTo("test");
        assertThat(linha.getCheckText()).isEqualTo("O plano tem seção de teste?");
        assertThat(linha.getPresence()).isEqualTo(Presence.ABSENT);
        assertThat(linha.getModel()).isEqualTo("qwen3:1.7b");
        assertThat(linha.getRunId()).isEqualTo(runId);
        assertThat(linha.getOwnerAudit()).isNull(); // a régua nasce por auditar
    }

    /**
     * O push lê o veredito do ÚLTIMO RUN inteiro, não a última linha. Aqui o run recente conferiu
     * só 1 checagem e o antigo tinha 2: misturar devolveria um checklist de duas execuções.
     */
    @Test
    void oVereditoMaisRecenteEEscopadoAoRunInteiroNaoALinhaMaisNova() {
        PlanValidationStore store = new PlanValidationStore(repository);
        UUID runRecente = UUID.randomUUID();
        when(repository.findFirstByIssueIdOrderByCreatedAtDesc(issueId))
                .thenReturn(Optional.of(linha(runRecente, "seam", Presence.ABSENT)));
        when(repository.findByRunIdOrderByCreatedAtAsc(runRecente))
                .thenReturn(List.of(linha(runRecente, "seam", Presence.ABSENT)));

        List<PlanCheckVerdict> verdicts = store.latestForIssue(issueId);

        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).checkId()).isEqualTo("seam");
        assertThat(verdicts.get(0).isHole()).isTrue();
    }

    @Test
    void issueSemVereditoNenhumDevolveVazioSemExplodir() {
        PlanValidationStore store = new PlanValidationStore(repository);
        when(repository.findFirstByIssueIdOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());

        assertThat(store.latestForIssue(issueId)).isEmpty();
    }

    private StoredPlanValidation linha(UUID runId, String checkId, Presence presence) {
        return new StoredPlanValidation(UUID.randomUUID(), companyId, runId, agentId, issueId,
                checkId, "texto da checagem", presence, "trecho", "qwen3:1.7b", Instant.now());
    }
}
