package com.orquestrador.preview;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.board.MissionControlService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.9 (ADR-0021): o fluxo INTEIRO da captura pelo pipeline real — o agente publica no contrato de
 * lugar DURANTE o run (process+LOCAL, sem credito), o executor captura APOS o sucesso e ANTES do
 * settle, e a Issue liquida normal. A captura e observacao: quando falha, o settle segue intocado.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewCaptureFlowTest {

    private static final String PUBLICA_PROTOTIPO =
            "mkdir -p .orq/preview && printf '<!doctype html><h1>oi</h1>' > .orq/preview/index.html";

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
    private PreviewArtifactRepository previews;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private ActivityLogRepository activity;
    @Autowired
    private MissionControlService missionControl;

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
    void runQuePublicaNoContratoGeraArtefatoEIssueLiquidaNormal() {
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, PUBLICA_PROTOTIPO);
        UUID issueId = issues.create(companyId, "criar prototipo", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        assertThat(versoes.get(0).getRunId()).isEqualTo(runId);
        assertThat(versoes.get(0).getContent()).isEqualTo("<!doctype html><h1>oi</h1>");
    }

    @Test
    void quandoAReviewNasceOArtefatoJaExiste() {
        // Ordem load-bearing (fechado §A.6): captura ANTES do settleAfterRun — a fila de Review
        // embute a versao EXATA do run sob revisao (review.run_id == preview_artifact.run_id).
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, PUBLICA_PROTOTIPO);
        UUID issueId = issues.create(companyId, "criar prototipo arriscado", null, 5).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.PENDING_REVIEW);
        Review cicloAberto = reviews
                .findByCompanyIdAndIssueIdAndStatus(companyId, issueId, ReviewStatus.PENDING).orElseThrow();
        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        assertThat(versoes.get(0).getRunId()).isEqualTo(cicloAberto.getRunId());
    }

    @Test
    void falhaDeCapturaNaoBloqueiaOSettle() {
        // Cap estourado: a Issue liquida normal (DONE), zero artefato, e o motivo vira registro
        // legivel (PREVIEW_CAPTURE_FAILED) — jamais excecao pro settle.
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId,
                "mkdir -p .orq/preview && head -c 2097153 /dev/zero > .orq/preview/index.html");
        UUID issueId = issues.create(companyId, "prototipo gigante", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class)).isEmpty();
        assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(companyId))
                .extracting(ActivityLog::getAction)
                .contains("PREVIEW_CAPTURE_FAILED");
    }

    @Test
    void cardDoMissionControlGanhaOBadgeDaUltimaCaptura() {
        // ADR-0021 §5: o Mission Control ganha SO o badge (cards leves, zero iframe no grid);
        // o clique leva a Issue — por isso o badge carrega issueId + id do artefato + versao.
        UUID companyId = novaCompany();
        UUID comPreview = agenteProcessLocal(companyId, PUBLICA_PROTOTIPO);
        UUID semPreview = agenteProcessLocal(companyId, "true");
        UUID issueId = issues.create(companyId, "criar prototipo", null).getId();
        wakeups.enqueue(companyId, comPreview, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        UUID artefatoId = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class)
                .get(0).getId();

        MissionControlService.Snapshot snapshot = missionControl.snapshot(companyId);

        MissionControlService.AgentCard cardComPreview = snapshot.agents().stream()
                .filter(card -> card.agent().id().equals(comPreview)).findFirst().orElseThrow();
        assertThat(cardComPreview.lastPreview()).isNotNull();
        assertThat(cardComPreview.lastPreview().previewArtifactId()).isEqualTo(artefatoId);
        assertThat(cardComPreview.lastPreview().issueId()).isEqualTo(issueId);
        assertThat(cardComPreview.lastPreview().version()).isEqualTo(1);
        assertThat(cardComPreview.lastPreview().createdAt()).isNotNull();

        MissionControlService.AgentCard cardSemPreview = snapshot.agents().stream()
                .filter(card -> card.agent().id().equals(semPreview)).findFirst().orElseThrow();
        assertThat(cardSemPreview.lastPreview()).isNull();
    }

    @Test
    void runQueFalhaNaoCaptura() {
        // Captura SO em SUCCEEDED: run falho -> BLOCKED, o trace e o canal (re-execucao re-publica).
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, PUBLICA_PROTOTIPO + " && exit 3");
        UUID issueId = issues.create(companyId, "prototipo que falha", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(companyId, issueId, PreviewArtifact.class)).isEmpty();
    }
}
