package com.orquestrador.planvalidation;

import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A seção que o arquiteto recebe no run N+1 (PUSH da R19-A). Só BURACOS factuais entram: um
 * "presente" não é notícia, e um "indeterminado" não é buraco — empurrá-lo seria o local
 * inventando trabalho, que é justamente o que o Inviolável §3.5 proíbe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanChecklistSectionTest {

    @Mock
    private PlanValidationStore store;
    @Mock
    private AgentRepository agents;

    private final UUID agentId = UUID.randomUUID();
    private final UUID issueId = UUID.randomUUID();

    @Test
    void soOsBuracosViramItensDoChecklist() {
        PlanChecklistSection section = section(PlanValidationProperties.Mode.PUSH, "architect");
        when(store.latestForIssue(issueId)).thenReturn(List.of(
                new PlanCheckVerdict("seam", Presence.PRESENT, "ReportService.java:142"),
                new PlanCheckVerdict("file-line", Presence.ABSENT, "nenhuma tarefa cita arquivo:linha"),
                new PlanCheckVerdict("test", Presence.INDETERMINATE, "")));

        String rendered = section.render(agentId, issueId).orElseThrow();

        assertThat(rendered).contains("## Checklist do validador");
        assertThat(rendered).contains("file-line");
        assertThat(rendered).contains("nenhuma tarefa cita arquivo:linha");
        assertThat(rendered).doesNotContain("seam");
        assertThat(rendered).doesNotContain("test");
    }

    @Test
    void planoSemBuracoNaoRendeSecaoNenhuma() {
        PlanChecklistSection section = section(PlanValidationProperties.Mode.PUSH, "architect");
        when(store.latestForIssue(issueId)).thenReturn(List.of(
                new PlanCheckVerdict("seam", Presence.PRESENT, "ReportService.java:142")));

        assertThat(section.render(agentId, issueId)).isEmpty();
    }

    /** Kill-switch GLOBAL: em sombra o veredito continua sendo gravado, mas nada é empurrado. */
    @Test
    void killSwitchGlobalDesligaOPushMasNaoATelemetria() {
        PlanChecklistSection section = section(PlanValidationProperties.Mode.SHADOW, "architect");
        when(store.latestForIssue(issueId)).thenReturn(List.of(
                new PlanCheckVerdict("file-line", Presence.ABSENT, "sem arquivo:linha")));

        assertThat(section.render(agentId, issueId)).isEmpty();
    }

    /** Kill-switch POR-PAPEL: o papel listado não recebe push nem com o modo global em PUSH. */
    @Test
    void killSwitchPorPapelDesligaOPushDaqueleCargo() {
        PlanValidationProperties properties = new PlanValidationProperties();
        properties.setMode(PlanValidationProperties.Mode.PUSH);
        properties.setPushDisabledRoles(List.of("architect"));
        PlanChecklistSection section = new PlanChecklistSection(store, agents, properties);
        when(agents.findById(agentId)).thenReturn(Optional.of(agenteComPapel("architect")));
        when(store.latestForIssue(issueId)).thenReturn(List.of(
                new PlanCheckVerdict("file-line", Presence.ABSENT, "sem arquivo:linha")));

        assertThat(section.render(agentId, issueId)).isEmpty();
    }

    private PlanChecklistSection section(PlanValidationProperties.Mode mode, String role) {
        PlanValidationProperties properties = new PlanValidationProperties();
        properties.setMode(mode);
        when(agents.findById(agentId)).thenReturn(Optional.of(agenteComPapel(role)));
        return new PlanChecklistSection(store, agents, properties);
    }

    private Agent agenteComPapel(String role) {
        return new Agent(agentId, UUID.randomUUID(), "Arquiteto", role, null);
    }
}
