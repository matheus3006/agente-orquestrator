package com.orquestrador.execution.runtime;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.preview.PreviewArtifact;
import com.orquestrador.preview.PreviewArtifactRepository;
import com.orquestrador.preview.server.PreviewServerThumbnail;
import com.orquestrador.preview.server.PreviewServerThumbnailRepository;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.14 (ADR-0028 §I.3): o round-trip dos repos que o {@code RowRetentionReaper} usa — contagem +
 * DELETE em LOTE (bulk JPQL, nunca carrega o content de 2MB) nas 3 tabelas, a página de ids do
 * firehose (o chunk), e o conjunto de runs fixados por review. H2 em modo Postgres (as entidades
 * mapeiam as FKs como UUID cru → sem constraint gerada, ids-pai aleatórios bastam).
 */
@SpringBootTest
@ActiveProfiles("test")
class RowRetentionRepositoryTest {

    @Autowired
    private PreviewServerThumbnailRepository thumbnails;
    @Autowired
    private PreviewArtifactRepository artifacts;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private ReviewRepository reviews;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private ConversationTurnRepository turns;

    @Test
    void deletaThumbnailsEmLoteEContaOResto() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID sobrevive = salvaThumbnail(companyId, issueId);
        List<UUID> colhe = List.of(salvaThumbnail(companyId, issueId), salvaThumbnail(companyId, issueId));

        assertThat(thumbnails.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(3);

        int removidos = thumbnails.deleteByIdIn(colhe);

        assertThat(removidos).isEqualTo(2);
        assertThat(thumbnails.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(1);
        assertThat(thumbnails.findByIdAndCompanyId(sobrevive, companyId)).isPresent();
    }

    @Test
    void deletaArtifactsEmLoteEContaOResto() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID sobrevive = salvaArtifact(companyId, issueId, 3);
        List<UUID> colhe = List.of(salvaArtifact(companyId, issueId, 1), salvaArtifact(companyId, issueId, 2));

        assertThat(artifacts.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(3);

        int removidos = artifacts.deleteByIdIn(colhe);

        assertThat(removidos).isEqualTo(2);
        assertThat(artifacts.countByCompanyIdAndIssueId(companyId, issueId)).isEqualTo(1);
        assertThat(artifacts.findByIdAndCompanyId(sobrevive, companyId)).isPresent();
    }

    @Test
    void deletaRunEventsPorPaginaDeIds_oChunkDoFirehose() {
        UUID companyId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID outroRun = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), runId, companyId, "STEP", null));
        }
        // um run de OUTRA Issue (fora do conjunto) nunca deve ser tocado
        runEvents.save(new HeartbeatRunEvent(UUID.randomUUID(), outroRun, companyId, "STEP", null));

        List<UUID> alvo = List.of(runId);
        assertThat(runEvents.countByRunIdIn(alvo)).isEqualTo(5);

        // O loop de chunk do reaper: página de 2 ids -> deleta -> repete até esvaziar.
        int removidos = 0;
        List<UUID> pagina;
        while (!(pagina = runEvents.findIdsByRunIdIn(alvo, PageRequest.of(0, 2))).isEmpty()) {
            assertThat(pagina).hasSizeLessThanOrEqualTo(2); // o chunk respeita o limite
            removidos += runEvents.deleteByIdIn(pagina);
        }

        assertThat(removidos).isEqualTo(5);
        assertThat(runEvents.countByRunIdIn(alvo)).isZero();
        assertThat(runEvents.countByRunIdIn(List.of(outroRun))).isEqualTo(1); // o de fora ficou
    }

    @Test
    void runIdsFixadosPorReviewDaIssue() {
        UUID companyId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        UUID runFixado = UUID.randomUUID();
        reviews.save(new Review(UUID.randomUUID(), companyId, issueId, runFixado));
        // review de OUTRA Issue não vaza para este conjunto (deny-by-default, liderado por company+issue)
        reviews.save(new Review(UUID.randomUUID(), companyId, UUID.randomUUID(), UUID.randomUUID()));

        List<UUID> pins = reviews.findRunIdsByCompanyIdAndIssueId(companyId, issueId);

        assertThat(pins).containsExactly(runFixado);
    }

    // ===== F.15 (adendo à ADR-0028): a query de enumeração da 2ª passada — turnos de conversa =====
    // vencidos pela âncora do RUN (D2 COALESCE(finished_at, created_at)), mirados SÓ por
    // conversation_turn.run_id (D4). É a "política" da conversa, declarativa no SQL.

    @Test
    @Transactional // o CLAIM real do turno (claimOldestPending) faz flush — exige transação, como em produção
    void enumeraRunDeTurnoDeConversaVencidoPelaAncoraDoRun() {
        UUID companyId = UUID.randomUUID();
        UUID runId = seedConversationTurnRun(companyId); // finished_at null, created_at ~agora

        // cutoff no FUTURO: tudo que foi criado antes dele está vencido.
        List<UUID> vencidos = runs.findConversationTurnRunIdsExpired(companyId, Instant.now().plusSeconds(3600));

        assertThat(vencidos).containsExactly(runId);
    }

    @Test
    @Transactional
    void naoEnumeraRunDeTurnoRecente() { // M5: o em-voo/recente escapa por construção (created_at recente)
        UUID companyId = UUID.randomUUID();
        seedConversationTurnRun(companyId); // created_at ~agora

        // cutoff no PASSADO: nada criado agora está vencido.
        List<UUID> vencidos = runs.findConversationTurnRunIdsExpired(companyId, Instant.now().minusSeconds(3600));

        assertThat(vencidos).isEmpty();
    }

    @Test
    @Transactional
    void naoEnumeraRunSemTurnoDeConversa() { // D4: mira conversation_turn.run_id, NÃO issue_id IS NULL
        UUID companyId = UUID.randomUUID();
        // um Run SEM turno de conversa (ex.: wakeup manual): issue_id NULL, mas nenhum turno o referencia.
        UUID runSemTurno = UUID.randomUUID();
        runs.save(new HeartbeatRun(runSemTurno, companyId, UUID.randomUUID(), null, RunStatus.SUCCEEDED));
        UUID runDeTurno = seedConversationTurnRun(companyId);

        List<UUID> vencidos = runs.findConversationTurnRunIdsExpired(companyId, Instant.now().plusSeconds(3600));

        assertThat(vencidos).containsExactly(runDeTurno); // só o turno; o wakeup (issue_id NULL) ficou de fora
    }

    @Test
    @Transactional
    void carimbaPeloCoalesceDeFinishedAtEDepoisCreatedAt() { // D2: COALESCE(finished_at, created_at)
        UUID companyId = UUID.randomUUID();
        // fantasma: finished_at NULL, created_at ~agora → o carimbo cai no created_at (recente).
        UUID fantasmaRecente = seedConversationTurnRun(companyId);
        // finalizado 1h atrás (created_at ~agora) → o carimbo cai no finished_at (antigo), não no created_at.
        UUID finalizadoAntigo = seedConversationTurnRunFinishedAt(companyId, Instant.now().minusSeconds(3600));

        // cutoff 30min atrás: só o finalizado (finished_at 1h atrás) está vencido.
        List<UUID> vencidos = runs.findConversationTurnRunIdsExpired(companyId, Instant.now().minusSeconds(1800));

        assertThat(vencidos).containsExactly(finalizadoAntigo); // finished_at (antigo) mandou no carimbo
        assertThat(vencidos).doesNotContain(fantasmaRecente);   // fallback pro created_at (recente) preservou
    }

    /** Semeia um Run de turno de conversa (issue_id NULL) e o preenche via o CLAIM real do motor. */
    private UUID seedConversationTurnRun(UUID companyId) {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        runs.save(new HeartbeatRun(runId, companyId, agentId, null, RunStatus.SUCCEEDED));
        turns.save(new ConversationTurn(UUID.randomUUID(), UUID.randomUUID(), companyId, agentId, "oi CEO"));
        turns.claimOldestPending(agentId, runId); // o motor preenche run_id (ConversationTurnCoordinator)
        return runId;
    }

    /** Idem, mas leva o Run pelo caminho real QUEUED→RUNNING→terminal para carimbar {@code finished_at}. */
    private UUID seedConversationTurnRunFinishedAt(UUID companyId, Instant finishedAt) {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String owner = "owner-" + runId;
        runs.save(new HeartbeatRun(runId, companyId, agentId, null, RunStatus.QUEUED));
        runs.claim(runId, RunStatus.QUEUED, RunStatus.RUNNING, owner, Instant.now().plusSeconds(60), Instant.now());
        runs.finalizeRun(runId, RunStatus.RUNNING, RunStatus.SUCCEEDED, owner, finishedAt, null);
        turns.save(new ConversationTurn(UUID.randomUUID(), UUID.randomUUID(), companyId, agentId, "oi CEO"));
        turns.claimOldestPending(agentId, runId);
        return runId;
    }

    private UUID salvaThumbnail(UUID companyId, UUID issueId) {
        UUID id = UUID.randomUUID();
        thumbnails.save(new PreviewServerThumbnail(id, companyId, issueId, UUID.randomUUID(),
                UUID.randomUUID(), new byte[]{1, 2, 3, 4}, "hash-" + id));
        return id;
    }

    private UUID salvaArtifact(UUID companyId, UUID issueId, int version) {
        UUID id = UUID.randomUUID();
        artifacts.save(new PreviewArtifact(id, companyId, issueId, UUID.randomUUID(), UUID.randomUUID(),
                version, "<html>v" + version + "</html>", "hash-" + id, "html", "index.html"));
        return id;
    }
}
