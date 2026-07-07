package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.12 (ADR-0026, B4): coletar mata a "app viva" para sempre (o volume :ro do PreviewServer some, e
 * DONE não reabre). A mitigação é um preflight no start que responde um aviso LEGÍVEL pelo caminho
 * de erro existente (StartResult.failure → StartResponse.error; front intocado) em vez de subir um
 * servidor sobre um volume vazio auto-criado pelo daemon. O sinal durável é o evento
 * WORKSPACE_COLLECTED no activity_log — que carrega a data.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerStartAfterGcTest {

    @Autowired
    private PreviewServerSupervisor supervisor;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ActivityLogService activityLog;

    @Test
    void startDeIssueComWorkspaceColetadoRespondeAvisoLegivel() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app de notas", null).getId();
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), "python3 -m http.server 8000", 8000));

        // O GC coletou o workspace desta Issue (o evento durável é o preflight).
        activityLog.record(companyId, ActorType.SYSTEM, null, ActivityAction.WORKSPACE_COLLECTED,
                "issue", issueId, Map.of("target", "DOCKER",
                        "workspaceRef", "orq_deadbeef_cafebabe_12345678"));

        PreviewServerSupervisor.StartResult resultado = supervisor.startFor(companyId, issueId, UUID.randomUUID());

        assertThat(resultado.failed()).isTrue();
        assertThat(resultado.error())
                .as("aviso legível, não um servidor sobre volume vazio")
                .containsIgnoringCase("coletado pelo GC");
    }
}
