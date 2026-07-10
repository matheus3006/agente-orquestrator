package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renovacao de lease (ADR-0005): enquanto o worker vive, o pulso estende o lease_expires_at —
 * o lease volta a ser sinal de vida confiavel. O {@link HeartbeatReaperTest} prova o lado
 * worker-MORTO (sem pulso -> requeue); aqui e o lado worker-VIVO.
 */
@SpringBootTest
@ActiveProfiles("test")
class LeaseRenewalTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private LeaseReaper reaper;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private HeartbeatRunEventRepository events;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID runClaimedBy(String owner) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, issueId, "trabalhar").runId();
        assertThat(store.claim(runId, owner, Duration.ofMinutes(2))).isTrue();
        return runId;
    }

    private void forceLease(UUID runId, Instant expiresAt) {
        jdbc.update("update heartbeat_run set lease_expires_at = ? where id = ?",
                Timestamp.from(expiresAt), runId);
    }

    private Instant leaseOf(UUID runId) {
        return runs.findById(runId).orElseThrow().getLeaseExpiresAt();
    }

    /**
     * Prova 2 — run renovado e invisivel ao reaper: mesmo com o lease ja VENCIDO (worker vivo
     * que demorou), o pulso que chega antes do reaper salva o Run do requeue. Sem o pulso, o
     * mesmo cenario termina em QUEUED ({@link HeartbeatReaperTest} prova esse lado).
     */
    @Test
    void runRenovadoEInvisivelAoReaper() {
        UUID runId = runClaimedBy("node-lento");
        forceLease(runId, Instant.now().minus(Duration.ofSeconds(30)));

        assertThat(store.renewLease(runId, "node-lento", Duration.ofMinutes(2))).isTrue();
        reaper.reapOnce();

        HeartbeatRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getAttempt()).isEqualTo(1);
        assertThat(run.getLeaseOwner()).isEqualTo("node-lento");
    }

    /** Prova 1 — o pulso estende: claim -> renova -> lease_expires_at andou. */
    @Test
    void pulsoEstendeOLease() {
        UUID runId = runClaimedBy("node-vivo");
        Instant pertoDoFim = Instant.now().plus(Duration.ofSeconds(10));
        forceLease(runId, pertoDoFim);

        assertThat(store.renewLease(runId, "node-vivo", Duration.ofMinutes(2))).isTrue();

        assertThat(leaseOf(runId)).isAfter(pertoDoFim);
    }

    /**
     * Prova 3 — o roteiro da sonda do laudo (§5), agora com a resposta construida: reaper requeia
     * worker VIVO, outro claim leva a MESMA linha, e a renovacao do dono original (a) devolve
     * false e (b) deixa RUN_LEASE_LOST no rastro — a perda de posse NUNCA mais e silenciosa.
     * O finalize do perdedor segue no-op sob o guard; o do novo dono vale.
     */
    @Test
    void posseParaOutroNoDeixaRunLeaseLostNoRastro() {
        UUID runId = runClaimedBy("node-A");
        forceLease(runId, Instant.now().minus(Duration.ofHours(1)));

        assertThat(reaper.reapOnce()).isGreaterThanOrEqualTo(1);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.claim(runId, "node-B", Duration.ofMinutes(2))).isTrue();
        assertThat(runs.findById(runId).orElseThrow().getAttempt()).isEqualTo(2);

        // o pulso do node-A descobre a perda: false + anti-silencio no rastro
        assertThat(store.renewLease(runId, "node-A", Duration.ofMinutes(2))).isFalse();
        List<HeartbeatRunEvent> perdas = events.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(event -> event.getType().equals("RUN_LEASE_LOST"))
                .toList();
        assertThat(perdas).hasSize(1);
        assertThat(perdas.getFirst().getDetail()).contains("node-A");

        // finalize do perdedor cai no vazio sob o guard; o do novo dono define o resultado
        store.finishSuccess(runId, "node-A");
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        store.finishSuccess(runId, "node-B");
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    /**
     * Prova 4 (a unica temporal) — o timer morre com o run: com a cadencia encurtada, o lease
     * estende >=1x durante a execucao; depois do finalize + close (a MESMA ordem do finally do
     * dispatcher), nada mais pulsa — o cancel() nao vaza timer. E o ultimo pulso perdedor da
     * corrida com o proprio finalize NAO vira RUN_LEASE_LOST (fim normal).
     */
    @Test
    void timerMorreComORun() throws Exception {
        LeaseRenewer renovadorApressado = new LeaseRenewer(store, 60);
        try {
            UUID runId = runClaimedBy("node-curto");
            Instant leaseDoClaim = leaseOf(runId);

            try (LeaseRenewer.Pulse pulse = renovadorApressado.start(runId, "node-curto",
                    Duration.ofMinutes(2))) {
                aguardarAte(() -> leaseOf(runId).isAfter(leaseDoClaim), Duration.ofSeconds(5));
                store.finishSuccess(runId, "node-curto");
            }

            Thread.sleep(200); // 3+ cadencias de folga para um pulso vazado aparecer
            Instant leaseAposFim = leaseOf(runId);
            Thread.sleep(200);
            assertThat(leaseOf(runId)).isEqualTo(leaseAposFim);
            assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(events.findByRunIdOrderByCreatedAtAsc(runId))
                    .noneMatch(event -> event.getType().equals("RUN_LEASE_LOST"));
        } finally {
            renovadorApressado.shutdown();
        }
    }

    private void aguardarAte(java.util.function.BooleanSupplier condicao, Duration teto)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (!condicao.getAsBoolean()) {
            assertThat(Instant.now()).as("condicao nao aconteceu dentro de %s", teto).isBefore(deadline);
            Thread.sleep(20);
        }
    }
}
