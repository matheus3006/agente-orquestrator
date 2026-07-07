package com.orquestrador.preview.server;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023 §2): o fluxo INTEIRO da declaracao pelo pipeline real — o agente publica o
 * server.json DURANTE o run (process+LOCAL, sem credito), o executor registra APOS o sucesso e
 * ANTES do settle (quando a review nasce, a declaracao ja existe — o card de Review compara
 * manifest.run_id com review.run_id para o "Rodar app").
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerDeclareFlowTest {

    private static final String DECLARA_SERVIDOR =
            "mkdir -p .orq/preview && printf '{\"command\": \"python3 -m http.server 3000\", \"port\": 3000}'"
                    + " > .orq/preview/server.json";

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private ReviewRepository reviews;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID agenteProcessLocal(UUID companyId, String command) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", command, "LOCAL");
        return agents.save(agent).getId();
    }

    @Test
    void runQueDeclaraServidorRegistraManifestEIssueLiquidaNormal() {
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, DECLARA_SERVIDOR);
        UUID issueId = issues.create(companyId, "criar app com servidor", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        PreviewServerManifest vigente = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId).orElseThrow();
        assertThat(vigente.getRunId()).isEqualTo(runId);
        assertThat(vigente.getCommand()).isEqualTo("python3 -m http.server 3000");
        assertThat(vigente.getPort()).isEqualTo(3000);
    }

    @Test
    void quandoAReviewNasceADeclaracaoJaExiste() {
        // Ordem load-bearing (mesma do capturador v1): registro ANTES do settleAfterRun — o card
        // de Review sabe que o run SOB REVISAO declarou (manifest.run_id == review.run_id).
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, DECLARA_SERVIDOR);
        UUID issueId = issues.create(companyId, "app com servidor arriscado", null, 5).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);
        Review cicloAberto = reviews
                .findByCompanyIdAndIssueIdAndStatus(companyId, issueId, ReviewStatus.PENDING).orElseThrow();
        PreviewServerManifest vigente = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId).orElseThrow();
        assertThat(vigente.getRunId()).isEqualTo(cicloAberto.getRunId());
    }

    @Test
    void runQueFalhaNaoRegistra() {
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, DECLARA_SERVIDOR + " && exit 3");
        UUID issueId = issues.create(companyId, "servidor que falha", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId)).isEmpty();
    }
}
