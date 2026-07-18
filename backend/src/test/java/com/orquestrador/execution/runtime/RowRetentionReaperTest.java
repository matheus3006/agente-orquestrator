package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.execution.AgentSession;
import com.orquestrador.execution.AgentSessionRepository;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyRepository;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.preview.PreviewArtifact;
import com.orquestrador.preview.PreviewArtifactRepository;
import com.orquestrador.preview.server.PreviewServerThumbnail;
import com.orquestrador.preview.server.PreviewServerThumbnailRepository;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * F.14 (ADR-0028): o RowRetentionReaper coleta a CAUDA superseded de Issues TERMINAIS vencidas nas 3
 * tabelas append-only MAS mantém o vigente por-Issue + o fixado por review (B3), com auditoria
 * RESUMO ator SYSTEM (B7). Molde do {@code WorkspaceReaperTest} (retenção 0 = coleta imediata do que
 * é terminal); os casos de config/best-effort constroem um reaper com deps reais e a config do caso.
 */
@SpringBootTest(properties = "orquestrador.rows.gc.retention-days=0")
@ActiveProfiles("test")
class RowRetentionReaperTest {

    @Autowired
    private RowRetentionReaper reaper; // retention-days=0 (coleta o terminal na hora)
    @Autowired
    private CompanyService companies;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private PreviewServerThumbnailRepository thumbnails;
    @Autowired
    private PreviewArtifactRepository artifacts;
    @Autowired
    private RowRetentionPolicy policy;
    @Autowired
    private ActivityLogService activityLog;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private DatabaseClock clock;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private AgentSessionRepository agentSessions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // ===== o coração: coleta a cauda, mantém o vigente, audita como SYSTEM =====

    @Test
    void coletaCaudaMasMantemOVigenteEAuditaResumoComoSystem() {
        Seed seed = seedTerminalIssueComCauda();

        RowRetentionReaper.SweepReport report = reaper.sweepCompany(seed.companyId());

        // coletou a cauda: 2 thumbnails antigos, 2 artifacts superseded, os 4 run_events
        assertThat(report.thumbnails()).isEqualTo(2);
        assertThat(report.artifacts()).isEqualTo(2);
        assertThat(report.runEvents()).isEqualTo(4);

        // o VIGENTE sobrevive VENDO: o último thumbnail e a maior version continuam abrindo
        assertThat(thumbnails.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId())).isEqualTo(1);
        assertThat(thumbnails.findByIdAndCompanyId(seed.vigenteThumbnail(), seed.companyId())).isPresent();
        assertThat(artifacts.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId())).isEqualTo(1);
        assertThat(artifacts.findByIdAndCompanyId(seed.vigenteArtifact(), seed.companyId())).isPresent();
        // o run FICA; só os filhos-evento vão
        assertThat(runEvents.countByRunIdIn(List.of(seed.eventsRunId()))).isZero();
        assertThat(runs.findById(seed.eventsRunId())).as("o run permanece — hub de FK").isPresent();

        // 1 evento-resumo ROWS_COLLECTED, ator SYSTEM, detail só-contagens
        List<ActivityLog> rowsCollected = activityLogRepository
                .findByCompanyIdOrderByCreatedAtDesc(seed.companyId()).stream()
                .filter(e -> "ROWS_COLLECTED".equals(e.getAction()))
                .toList();
        assertThat(rowsCollected).hasSize(1);
        ActivityLog evento = rowsCollected.get(0);
        assertThat(evento.getActorType()).isEqualTo(ActorType.SYSTEM.name());
        assertThat(evento.getActorId()).isNull();
        assertThat(evento.getEntityId()).isEqualTo(seed.companyId());
        assertThat(evento.getDetail())
                .contains("\"thumbnail\":2").contains("\"artifact\":2").contains("\"run_events\":4");
    }

    @Test
    void mantemAVersaoDeArtifactFIXADAPorReviewMesmoSuperseded() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = doneIssue(companyId, agentId);

        UUID runV1 = UUID.randomUUID();
        UUID runV2 = UUID.randomUUID();
        UUID runV3 = UUID.randomUUID();
        UUID v1 = saveArtifact(companyId, issueId, agentId, 1, runV1);
        UUID v2 = saveArtifact(companyId, issueId, agentId, 2, runV2);
        UUID v3 = saveArtifact(companyId, issueId, agentId, 3, runV3);
        reviews.save(new Review(UUID.randomUUID(), companyId, issueId, runV2)); // "R aprovou a v2"

        reaper.sweepCompany(companyId);

        assertThat(artifacts.findByIdAndCompanyId(v1, companyId)).as("superseded não-fixada: coletada").isEmpty();
        assertThat(artifacts.findByIdAndCompanyId(v2, companyId)).as("fixada por review: eterna").isPresent();
        assertThat(artifacts.findByIdAndCompanyId(v3, companyId)).as("vigente (maior version): eterna").isPresent();
    }

    // ===== proteções por construção =====

    @Test
    void naoColetaNadaDeIssueNaoTerminal() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "em progresso", null).getId();
        issues.checkout(companyId, issueId, agentId, UUID.randomUUID()); // IN_PROGRESS (não-terminal)

        UUID t1 = saveThumbnail(companyId, issueId, agentId);
        saveThumbnail(companyId, issueId, agentId);
        saveArtifact(companyId, issueId, agentId, 1, UUID.randomUUID());
        saveArtifact(companyId, issueId, agentId, 2, UUID.randomUUID());
        UUID runId = UUID.randomUUID();
        runs.save(new HeartbeatRun(runId, companyId, agentId, issueId, RunStatus.RUNNING));
        runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), runId, companyId, "STEP", null));

        RowRetentionReaper.SweepReport report = reaper.sweepCompany(companyId);

        assertThat(report.total()).isZero();
        assertThat(thumbnails.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(2);
        assertThat(thumbnails.findByIdAndCompanyId(t1, companyId)).isPresent();
        assertThat(artifacts.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(2);
        assertThat(runEvents.countByRunIdIn(List.of(runId))).isEqualTo(1);
        assertThat(rowsCollectedCount(companyId)).isZero();
    }

    @Test
    void retencaoNaoVencidaMantemTudo() {
        Seed seed = seedTerminalIssueComCauda(); // updated_at ~ agora
        RowRetentionReaper dezAnos = reaperWith(issueRepository, true, 3650);

        dezAnos.sweepCompany(seed.companyId());

        assertThat(thumbnails.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId()))
                .as("Issue terminal DENTRO da retenção: cauda preservada").isEqualTo(3);
        assertThat(artifacts.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId())).isEqualTo(3);
        assertThat(runEvents.countByRunIdIn(List.of(seed.eventsRunId()))).isEqualTo(4);
        assertThat(rowsCollectedCount(seed.companyId())).isZero();
    }

    @Test
    void killSwitchDesligadoNaoColetaNada() {
        Seed seed = seedTerminalIssueComCauda();
        RowRetentionReaper desligado = reaperWith(issueRepository, false, 0);

        desligado.sweepOnce();

        assertThat(thumbnails.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId())).isEqualTo(3);
        assertThat(artifacts.countByCompanyIdAndIssueId(seed.companyId(), seed.issueId())).isEqualTo(3);
        assertThat(rowsCollectedCount(seed.companyId())).isZero();
    }

    @Test
    void bestEffort_falhaNumaCompanyNaoAbortaAsDemais() {
        Seed saudavel = seedTerminalIssueComCauda();
        Seed envenenada = seedTerminalIssueComCauda();

        // a company envenenada estoura ao listar suas Issues; a saudável deve coletar mesmo assim.
        IssueRepository issuesComFalha = mock(IssueRepository.class, delegatesTo(issueRepository));
        doThrow(new RuntimeException("boom sintético"))
                .when(issuesComFalha).findByCompanyIdAndStatusIn(eq(envenenada.companyId()), any());
        RowRetentionReaper reaperBestEffort = reaperWith(issuesComFalha, true, 0);

        reaperBestEffort.sweepOnce(); // NÃO deve propagar a exceção

        assertThat(thumbnails.countByCompanyIdAndIssueId(saudavel.companyId(), saudavel.issueId()))
                .as("a company saudável foi coletada apesar da falha na envenenada").isEqualTo(1);
        assertThat(thumbnails.countByCompanyIdAndIssueId(envenenada.companyId(), envenenada.issueId()))
                .as("a company envenenada ficou intacta (estourou antes de deletar)").isEqualTo(3);
    }

    // ===== F.15 (adendo à ADR-0028): a 2ª passada — o rastro dos turnos de conversa do CEO =====

    @Test
    void coletaRastroDoTurnoVencidoMasPreservaTextoSessionERun() {
        ConvSeed seed = seedTurnoDeConversaComRastro(newCompany(), RunStatus.SUCCEEDED, 3);

        RowRetentionReaper.SweepReport report = reaper.sweepCompany(seed.companyId()); // retention=0

        // o rastro (run_events) do turno vencido foi coletado...
        assertThat(report.conversationRunEvents()).isEqualTo(3);
        assertThat(runEvents.countByRunIdIn(List.of(seed.runId()))).isZero();
        // ...MAS o durável sobrevive: o texto do turno, o handle do motor (agent_session), e o próprio Run.
        ConversationTurn turno = turns.findById(seed.turnId()).orElseThrow();
        assertThat(turno.getOwnerMessage()).isEqualTo("pergunta do dono");
        assertThat(turno.getCeoReply()).isEqualTo("resposta do CEO");
        assertThat(turno.getRunId()).as("o elo run_id do turno segue válido").isEqualTo(seed.runId());
        assertThat(agentSessions.findById(seed.sessionId())).as("o handle do motor sobrevive").isPresent();
        assertThat(runs.findById(seed.runId())).as("o Run permanece — só os filhos-evento vão").isPresent();

        // auditoria: 1 evento-resumo ROWS_COLLECTED, ator SYSTEM, com o bucket de conversa contando certo.
        List<ActivityLog> rowsCollected = activityLogRepository
                .findByCompanyIdOrderByCreatedAtDesc(seed.companyId()).stream()
                .filter(e -> "ROWS_COLLECTED".equals(e.getAction()))
                .toList();
        assertThat(rowsCollected).hasSize(1);
        ActivityLog evento = rowsCollected.get(0);
        assertThat(evento.getActorType()).isEqualTo(ActorType.SYSTEM.name());
        assertThat(evento.getActorId()).isNull();
        assertThat(evento.getEntityId()).isEqualTo(seed.companyId());
        assertThat(evento.getDetail()).contains("\"conversation_run_events\":3");
    }

    @Test
    void turnoRecenteNaoPerdeORastro() { // M5: o em-voo/recente escapa por construção (created_at recente)
        ConvSeed seed = seedTurnoDeConversaComRastro(newCompany(), RunStatus.RUNNING, 4);
        RowRetentionReaper dezAnos = reaperWith(issueRepository, true, 3650);

        dezAnos.sweepCompany(seed.companyId());

        assertThat(runEvents.countByRunIdIn(List.of(seed.runId())))
                .as("turno recente/em-voo: rastro preservado").isEqualTo(4);
        assertThat(rowsCollectedCount(seed.companyId())).isZero();
    }

    @Test
    void naoColetaRunSemIssueQueNaoETurnoDeConversa() { // D4: miramos conversation_turn.run_id, não issue_id IS NULL
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        // um wakeup manual: Run SEM Issue (issue_id NULL) e SEM conversation_turn apontando — rastro antigo.
        UUID wakeupRun = UUID.randomUUID();
        runs.save(new HeartbeatRun(wakeupRun, companyId, agentId, null, RunStatus.SUCCEEDED));
        runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), wakeupRun, companyId, "STEP", "wakeup"));

        RowRetentionReaper.SweepReport report = reaper.sweepCompany(companyId); // retention=0

        assertThat(report.conversationRunEvents()).isZero();
        assertThat(runEvents.countByRunIdIn(List.of(wakeupRun)))
                .as("wakeup sem Issue e sem turno NÃO é coletado").isEqualTo(1);
        assertThat(rowsCollectedCount(companyId)).isZero();
    }

    @Test
    void oResumoUnicoCarregaOsDoisBucketsIssueEConversa() { // §D.6: F.14 e F.15 num só ROWS_COLLECTED
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        // cauda de Issue terminal-vencida (F.14): 1 run da Issue + 3 eventos.
        UUID issueId = doneIssue(companyId, agentId);
        UUID issueRun = UUID.randomUUID();
        runs.save(new HeartbeatRun(issueRun, companyId, agentId, issueId, RunStatus.SUCCEEDED));
        for (int i = 0; i < 3; i++) {
            runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), issueRun, companyId, "STEP", "i=" + i));
        }
        // rastro de turno de conversa (F.15): 2 eventos.
        ConvSeed conv = seedTurnoDeConversaComRastro(companyId, RunStatus.SUCCEEDED, 2);

        RowRetentionReaper.SweepReport report = reaper.sweepCompany(companyId); // retention=0

        assertThat(report.runEvents()).as("a passada de Issue (F.14) segue coletando").isEqualTo(3);
        assertThat(report.conversationRunEvents()).as("a 2ª passada de conversa (F.15)").isEqualTo(2);
        assertThat(runEvents.countByRunIdIn(List.of(issueRun))).isZero();
        assertThat(runEvents.countByRunIdIn(List.of(conv.runId()))).isZero();

        // UM só evento-resumo por sweep-por-company, com OS DOIS buckets contando cada fonte.
        List<ActivityLog> rowsCollected = activityLogRepository
                .findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(e -> "ROWS_COLLECTED".equals(e.getAction()))
                .toList();
        assertThat(rowsCollected).hasSize(1);
        assertThat(rowsCollected.get(0).getDetail())
                .contains("\"run_events\":3").contains("\"conversation_run_events\":2");
    }

    // ===== helpers de seed =====

    /** O retrato de uma Issue terminal vencida com cauda: os ids que devem SOBREVIVER à coleta. */
    private record Seed(UUID companyId, UUID issueId, UUID vigenteThumbnail, UUID vigenteArtifact,
                        UUID eventsRunId) {
    }

    /** O retrato de um turno de conversa com rastro: o que sobrevive (texto/session/run) e quantos eventos. */
    private record ConvSeed(UUID companyId, UUID turnId, UUID runId, UUID sessionId, int eventos) {
    }

    /**
     * Semeia um turno ANSWERED (owner_message + ceo_reply — o texto durável) com seu Run de conversa
     * (issue_id NULL) e {@code eventos} run_events (o rastro), mais a conversa e o handle do motor
     * (agent_session) que a faxina JAMAIS toca. O {@code run_id} é preenchido pelo CLAIM real do motor.
     */
    private ConvSeed seedTurnoDeConversaComRastro(UUID companyId, RunStatus runStatus, int eventos) {
        UUID agentId = newAgent(companyId);
        UUID conversationId = UUID.randomUUID();
        conversations.save(new Conversation(conversationId, companyId, "Assunto " + conversationId));
        UUID sessionId = UUID.randomUUID();
        agentSessions.save(new AgentSession(sessionId, companyId, agentId, conversationId, "claude"));

        UUID runId = UUID.randomUUID();
        runs.save(new HeartbeatRun(runId, companyId, agentId, null, runStatus)); // issue_id NULL = turno
        for (int i = 0; i < eventos; i++) {
            runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), runId, companyId, "STEP", "n=" + i));
        }

        UUID turnId = UUID.randomUUID();
        turns.save(new ConversationTurn(turnId, conversationId, companyId, agentId, "pergunta do dono"));
        inTx(() -> turns.claimOldestPending(agentId, runId)); // o motor preenche run_id (flush exige transação)
        ConversationTurn turno = turns.findById(turnId).orElseThrow();
        turno.answer("resposta do CEO");
        turns.save(turno);
        return new ConvSeed(companyId, turnId, runId, sessionId, eventos);
    }

    /** Roda uma escrita que faz flush (o CLAIM do motor) dentro de uma transação, como em produção. */
    private void inTx(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private Seed seedTerminalIssueComCauda() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = doneIssue(companyId, agentId);

        // 3 thumbnails por-run (o vigente = o ÚLTIMO por created_at); sleep p/ ordenar o created_at.
        saveThumbnail(companyId, issueId, agentId);
        sleepMillis(2);
        saveThumbnail(companyId, issueId, agentId);
        sleepMillis(2);
        saveThumbnail(companyId, issueId, agentId);
        UUID vigenteThumbnail = thumbnails
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId).orElseThrow().getId();

        // 3 versions de artifact (o vigente = a MAIOR version, única por Issue).
        saveArtifact(companyId, issueId, agentId, 1, UUID.randomUUID());
        saveArtifact(companyId, issueId, agentId, 2, UUID.randomUUID());
        UUID vigenteArtifact = saveArtifact(companyId, issueId, agentId, 3, UUID.randomUUID());

        // um run da Issue + 4 eventos (o firehose): o run fica, os eventos vão.
        UUID eventsRunId = UUID.randomUUID();
        runs.save(new HeartbeatRun(eventsRunId, companyId, agentId, issueId, RunStatus.SUCCEEDED));
        for (int i = 0; i < 4; i++) {
            runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), eventsRunId, companyId, "STEP", "n=" + i));
        }
        return new Seed(companyId, issueId, vigenteThumbnail, vigenteArtifact, eventsRunId);
    }

    private RowRetentionReaper reaperWith(IssueRepository issuesRepo, boolean enabled, long retentionDays) {
        return new RowRetentionReaper(companyRepository, issuesRepo, reviews, runs, thumbnails, artifacts,
                runEvents, policy, activityLog, clock, enabled, retentionDays, 500);
    }

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        return agents.save(agent).getId();
    }

    private UUID doneIssue(UUID companyId, UUID agentId) {
        UUID issueId = issues.create(companyId, "app com cauda", null).getId();
        UUID lockRun = UUID.randomUUID();
        issues.checkout(companyId, issueId, agentId, lockRun);
        issues.complete(companyId, issueId, lockRun); // risco 1 < limiar -> DONE (terminal)
        return issueId;
    }

    private UUID saveThumbnail(UUID companyId, UUID issueId, UUID agentId) {
        UUID id = UUID.randomUUID();
        thumbnails.save(new PreviewServerThumbnail(id, companyId, issueId, agentId, UUID.randomUUID(),
                new byte[]{1, 2, 3, 4}, "thumb-" + id));
        return id;
    }

    private UUID saveArtifact(UUID companyId, UUID issueId, UUID agentId, int version, UUID runId) {
        UUID id = UUID.randomUUID();
        artifacts.save(new PreviewArtifact(id, companyId, issueId, agentId, runId, version,
                "<html>v" + version + "</html>", "art-" + id, "html", "index.html"));
        return id;
    }

    private long rowsCollectedCount(UUID companyId) {
        return activityLogRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(e -> "ROWS_COLLECTED".equals(e.getAction()))
                .count();
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
