package com.orquestrador.execution;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pulso de vida do worker (ADR-0005): enquanto um Run executa, estende o lease a cada intervalo
 * (30s -> lease cheio de 2min: da para perder 3 pulsos seguidos antes de o reaper poder tocar o
 * Run). O timer nasce no claim e morre no finally do dispatcher — JVM morta = pulso morto =
 * morte presumida, sem logica extra. Thread PROPRIA por necessidade: a unica thread do
 * scheduler e exatamente a que fica bloqueada dentro do run sincrono.
 *
 * <p>Perder a posse (renovacao = 0 linhas) para o pulso SEM matar o processo: o run termina em
 * paz e o guard por dono do finalizeRun ja impede que o resultado sobrescreva o do novo dono.
 */
@Service
public class LeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewer.class);

    private final HeartbeatRunStore store;
    private final long renewIntervalMs;
    private final ScheduledExecutorService pulses;

    public LeaseRenewer(HeartbeatRunStore store,
                        @Value("${orquestrador.heartbeat.lease-renew-ms:30000}") long renewIntervalMs) {
        this.store = store;
        this.renewIntervalMs = renewIntervalMs;
        this.pulses = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Liga o pulso de um Run recem-claimado; fechar o handle desliga (idempotente). */
    public Pulse start(UUID runId, String owner, Duration lease) {
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        ScheduledFuture<?> future = pulses.scheduleAtFixedRate(() -> {
            // Excecao NUNCA escapa da task: mataria a repeticao em silencio — a propria
            // armadilha que a folga de 3 pulsos existe para tolerar. Transiente = insiste.
            try {
                if (!store.renewLease(runId, owner, lease)) {
                    log.warn("run {}: posse do lease perdida por {} — pulso encerrado, o run termina em paz",
                            runId, owner);
                    ScheduledFuture<?> pulso = self.get();
                    if (pulso != null) {
                        pulso.cancel(false);
                    }
                }
            } catch (Exception exception) {
                log.warn("run {}: pulso de lease falhou ({}); insiste no proximo", runId,
                        exception.getMessage());
            }
        }, renewIntervalMs, renewIntervalMs, TimeUnit.MILLISECONDS);
        self.set(future);
        return new Pulse(future);
    }

    @PreDestroy
    public void shutdown() {
        pulses.shutdownNow();
    }

    /** Handle do pulso de um Run: a vida do timer amarrada ao try do dispatcher por construcao. */
    public static final class Pulse implements AutoCloseable {

        private final ScheduledFuture<?> future;

        private Pulse(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }
}
