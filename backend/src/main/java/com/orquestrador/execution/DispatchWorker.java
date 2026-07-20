package com.orquestrador.execution;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A passada de dispatch FORA da thread do scheduler (ADR-0050, 2ª trava).
 *
 * <p>O {@code HeartbeatDispatcher.dispatchOnce} executa o run do agente <b>sincronamente</b>. Com
 * ele pendurado direto no {@code @Scheduled}, o run inteiro ocupava {@code scheduling-1} — a thread
 * ÚNICA que o Boot cria por omissão de {@code spring.task.scheduling.pool.size} — e levava junto o
 * lease-reaper, o GC de workspace, o GC de preview e os thumbnails. Nenhum erro aparecia: as coisas
 * simplesmente deixavam de acontecer, que é o modo de falha mais caro de diagnosticar.
 *
 * <p><b>O limite explícito é UM.</b> Uma thread, uma passada em voo. Não é timidez: hoje o
 * {@code fixedDelay} do scheduler só re-agenda depois do retorno, então nunca existiram duas
 * passadas simultâneas, e este worker <b>preserva</b> essa garantia em vez de estreá-la junto com a
 * mudança de thread. Subir esse número é decisão de capacidade — o claim por CAS do
 * {@code HeartbeatRunStore} já tornaria seguro — e não pertence a um conserto de robustez.
 *
 * <p><b>Pular, nunca enfileirar.</b> O tick de 1s continua chegando enquanto uma passada longa roda;
 * enfileirar cada um criaria uma fila que nunca drena e transformaria uma trava em avalanche. O tick
 * que encontra passada em voo é descartado — o próximo, um segundo depois, tenta de novo.
 */
@Component
@ConditionalOnProperty(name = "orquestrador.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

    private final HeartbeatDispatcher dispatcher;
    private final ExecutorService passadas;
    private final AtomicBoolean emVoo = new AtomicBoolean(false);

    public DispatchWorker(HeartbeatDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.passadas = Executors.newSingleThreadExecutor(tarefa -> {
            Thread thread = new Thread(tarefa, "dispatch-worker");
            // Daemon pelo mesmo motivo do pulso de lease: um run pendurado não pode ser o que
            // impede a JVM de descer.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Dispara uma passada, se não houver outra em voo.
     *
     * @return {@code true} se ESTA chamada iniciou a passada; {@code false} se o tick foi pulado
     *         (passada em andamento, ou aplicação descendo).
     */
    public boolean dispatchAsync() {
        if (!emVoo.compareAndSet(false, true)) {
            return false;
        }
        try {
            passadas.execute(this::passada);
            return true;
        } catch (RejectedExecutionException descendo) {
            // A porta tem de voltar ao lugar mesmo quando ninguém vai atravessá-la.
            emVoo.set(false);
            return false;
        }
    }

    /**
     * A passada em si. O {@code finally} é o que impede a parada silenciosa por outro caminho: sem
     * ele, a primeira passada que explodisse deixaria {@code emVoo} travado em {@code true} e todo
     * tick seguinte seria pulado para sempre — motor parado, de novo sem erro visível.
     */
    private void passada() {
        try {
            dispatcher.dispatchOnce();
        } catch (RuntimeException falha) {
            log.warn("passada de dispatch falhou ({}) — a porta é liberada e o próximo tick tenta de novo",
                    falha.toString());
        } finally {
            emVoo.set(false);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        passadas.shutdownNow();
    }
}
