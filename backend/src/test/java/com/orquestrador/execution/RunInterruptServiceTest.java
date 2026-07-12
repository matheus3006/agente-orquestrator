package com.orquestrador.execution;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.LocalExecutionTarget;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N4 (ADR-0040, D4): o endpoint de interrupt e este servico — CAS RUNNING->INTERRUPTED
 * PRIMEIRO (vence a corrida com o finishFailure natural por construcao, {@link RunInterruptTest}),
 * SO DEPOIS mata o processo pelo ExecutionTarget resolvido do Agent (mesmo caminho de resolucao
 * de {@code AdapterRunExecutor}: {@code targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()))}).
 */
@SpringBootTest
@ActiveProfiles("test")
class RunInterruptServiceTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private RunInterruptService interruptService;
    @Autowired
    private LocalExecutionTarget localTarget;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void interrompeUmRunVivoEMataOProcessoRealDoAlvoLocal(@org.junit.jupiter.api.io.TempDir Path workDir)
            throws Exception {
        UUID companyId = novaCompany();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId();
        assertThat(store.claim(runId, "node-x", Duration.ofMinutes(2))).isTrue();

        // O agente por-default roda LOCAL (Agent.executionTarget="LOCAL") — o mesmo bean que o
        // servico vai resolver. Simula o run em andamento: um processo real registrado sob runId.
        Path pidFile = workDir.resolve("childpid");
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(workDir,
                List.of("sh", "-c", "cat /dev/zero > /dev/null & echo $! > childpid; wait"),
                Map.of(), null, null, List.of());
        CompletableFuture<ExecutionTarget.Result> execucao = CompletableFuture.supplyAsync(
                () -> localTarget.run(spec, runId, line -> { }));
        long childPid = aguardarPid(pidFile, Duration.ofSeconds(5));

        RunInterruptService.InterruptOutcome outcome = interruptService.interrupt(companyId, runId, "dono pediu parar");

        assertThat(outcome.status()).isEqualTo(RunInterruptService.InterruptOutcome.Status.INTERRUPTED);
        assertThat(outcome.processKilled()).isTrue();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        execucao.get(5, TimeUnit.SECONDS); // o run() de fundo retorna (o processo de fato morreu)
        aguardarMorte(childPid, Duration.ofSeconds(5));
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void interromperRunQueNaoEstaRodandoDevolveNotRunning() {
        UUID companyId = novaCompany();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId(); // QUEUED

        RunInterruptService.InterruptOutcome outcome = interruptService.interrupt(companyId, runId, "tarde demais");

        assertThat(outcome.status()).isEqualTo(RunInterruptService.InterruptOutcome.Status.NOT_RUNNING);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void interromperRunDeOutraCompanyDevolveNotFound() {
        UUID companyId = novaCompany();
        UUID outraCompanyId = novaCompany();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = wakeups.enqueue(companyId, agentId, null, "trabalhar").runId();
        assertThat(store.claim(runId, "node-x", Duration.ofMinutes(2))).isTrue();

        RunInterruptService.InterruptOutcome outcome = interruptService.interrupt(outraCompanyId, runId, "x");

        assertThat(outcome.status()).isEqualTo(RunInterruptService.InterruptOutcome.Status.NOT_FOUND);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    private static long aguardarPid(Path pidFile, Duration teto) throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(pidFile)) {
                try {
                    String conteudo = Files.readString(pidFile).trim();
                    if (!conteudo.isEmpty()) {
                        return Long.parseLong(conteudo);
                    }
                } catch (Exception aindaEscrevendo) {
                    // arquivo apareceu mas o conteudo ainda nao foi flushado — tenta de novo
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("child pid nao apareceu em " + teto);
    }

    private static void aguardarMorte(long pid, Duration teto) throws InterruptedException {
        Instant deadline = Instant.now().plus(teto);
        while (Instant.now().isBefore(deadline)) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
