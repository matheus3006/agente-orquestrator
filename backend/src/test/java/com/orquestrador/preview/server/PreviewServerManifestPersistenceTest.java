package com.orquestrador.preview.server;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023): preview_server_manifest guarda SO a DECLARACAO — append-only por run, sem
 * dedup, sem status. O vigente de uma Issue e o ULTIMO registrado (runs serializados pelo
 * execution_lock); o runtime (vivo/porta) NUNCA mora aqui — mora no Docker por label.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerManifestPersistenceTest {

    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private Cenario cenario() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app com servidor", null).getId();
        return new Cenario(companyId, agentId, issueId);
    }

    @Test
    void declaracaoPersistePorRunComComandoEPorta() {
        Cenario cenario = cenario();
        UUID runId = UUID.randomUUID();

        manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), runId, "node server.js", 3000));

        PreviewServerManifest vigente = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(cenario.companyId(), cenario.issueId())
                .orElseThrow();
        assertThat(vigente.getCommand()).isEqualTo("node server.js");
        assertThat(vigente.getPort()).isEqualTo(3000);
        assertThat(vigente.getRunId()).isEqualTo(runId);
        assertThat(vigente.getCreatedAt()).isNotNull();
    }

    @Test
    void appendOnlySemDedupOVigenteEOUltimoDaIssue() {
        // Run novo re-declara (mesmo comando ou nao): linha NOVA — subir usa sempre o ultimo.
        Cenario cenario = cenario();
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(), "node server.js", 3000));
        UUID segundoRun = UUID.randomUUID();

        manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), segundoRun, "python3 app.py", 8000));

        assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).hasSize(2);
        PreviewServerManifest vigente = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(cenario.companyId(), cenario.issueId())
                .orElseThrow();
        assertThat(vigente.getCommand()).isEqualTo("python3 app.py");
        assertThat(vigente.getRunId()).isEqualTo(segundoRun);
    }
}
