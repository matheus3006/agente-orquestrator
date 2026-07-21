package com.orquestrador.execution;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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

    /**
     * O prazo que o encerramento espera pela passada em voo — ver {@link #close()}. Curto e
     * explícito de propósito: quem espera aqui é o shutdown da aplicação, e um teto generoso viraria
     * uma parada que parece travamento. Cinco segundos cobrem com folga a derrubada real de um
     * container ({@code docker rm -f}) sem chegar perto do teto patológico de 30s do comando curto —
     * abaixo dele porque este é o orçamento do SHUTDOWN, não o do comando.
     *
     * <p><b>"Uma derrubada" só virou verdade com a checagem de interrupção no laço de
     * {@code HeartbeatDispatcher.dispatchOnce}.</b> Sem ela, o laço seguia reivindicando os próximos
     * runs {@code QUEUED} depois do {@code shutdownNow} — até 50 —, e estes 5s eram divididos por
     * todos eles: o limite escrito aqui não era o limite real.
     *
     * <p><b>Acoplamento nomeado, deliberadamente NÃO derivado.</b> Este número é
     * {@code private static final} enquanto o {@code comando-curto} é property com setter público:
     * um operador que o suba para 120s alarga o gap de 6× para 24× sem aviso, log ou teste. Derivar
     * o teto do {@code close()} do {@code comando-curto} foi considerado e recusado — são grandezas
     * diferentes (paciência com um daemon lento × quanto a parada da aplicação pode pendurar), e
     * derivar deixaria uma tunagem de latência redefinir em silêncio a duração do shutdown. O que o
     * gap produz é o desfecho já declarado na ADR-0050: estourado o teto, a parada segue com WARN e
     * aquela derrubada pode não completar.
     */
    private static final long PRAZO_DE_ENCERRAMENTO_SEGUNDOS = 5;

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
        } catch (Throwable falha) {
            // {@code Throwable}, não {@code RuntimeException}: um {@code Error} escapava deste catch e
            // ia só ao handler default da thread. Não travava — o {@code finally} abaixo libera a porta
            // e o pool respawna — mas a linha de diagnóstico, único rastro de que a passada caiu,
            // sumia junto. Perder o log de um {@code Error} é perder a pista da falha mais grave.
            log.warn("passada de dispatch falhou ({}) — a porta é liberada e o próximo tick tenta de novo",
                    falha.toString());
            // Logar não é engolir. {@code Error} não é "falha de passada", é a JVM em apuros
            // (OOM, pilha estourada): a linha fica, mas ele SOBE. Um OutOfMemoryError silenciado aqui
            // esconderia a causa real e faria o motor parecer apenas lento.
            if (falha instanceof Error erroDaJvm) {
                throw erroDaJvm;
            }
        } finally {
            emVoo.set(false);
        }
    }

    /**
     * <b>Esperar é parte do encerramento.</b> {@code shutdownNow} INTERROMPE a passada em voo; ele
     * não a conclui — e o que a interrupção DISPARA é justamente trabalho de encerramento que leva
     * tempo: a passada desce por {@code ProcessoComPrazo.aguardar}, que escala a derrubada do
     * trabalho do agente antes de propagar. E a thread do dispatch é DAEMON — sem esperar aqui,
     * {@code close()} volta e a JVM sai por cima da derrubada em curso. É o MESMO desfecho que a
     * ordem do interrupt causava dentro do {@code ProcessoComPrazo}, alcançado por outro caminho;
     * fechar só um dos dois deixaria a garantia meia-verdadeira.
     *
     * <p><b>Onde a JVM que sai por cima realmente órfã trabalho: o alvo LOCAL.</b> Lá a derrubada é
     * {@code RunProcessRegistry.interrupt} — {@code destroyForcibly()} nos descendentes e no processo
     * raiz, tudo DE DENTRO da JVM. Morrendo a JVM antes disso, o agente local e sua árvore ficam
     * vivos, sem {@code --name} e sem reaper que os re-adote (o processo local não tem label).
     * <b>Para o caminho Docker o exemplo do {@code docker rm -f} não vale ao pé da letra</b>: um
     * filho de {@code ProcessBuilder.start()} não morre com a JVM em Unix — é reparentado e termina
     * sozinho —, então ali a espera protege a janela ANTES do fork (montar argv, spawnar), não a
     * execução do cliente já nascido. A espera continua necessária; o exemplo é que overclaimava.
     *
     * <p>Esperar é best-effort, e o teto é o que impede a garantia de virar travamento: passado o
     * prazo o encerramento segue mesmo assim, com WARN — perder a derrubada de um container é ruim,
     * pendurar o shutdown da aplicação para sempre é pior. A interrupção que chegar a ESTA thread
     * não é engolida.
     */
    @Override
    @PreDestroy
    public void close() {
        passadas.shutdownNow();
        try {
            if (!passadas.awaitTermination(PRAZO_DE_ENCERRAMENTO_SEGUNDOS, TimeUnit.SECONDS)) {
                log.warn("a passada de dispatch não encerrou em {}s depois do shutdownNow — seguindo"
                                + " com a parada; trabalho de derrubada em curso pode não completar",
                        PRAZO_DE_ENCERRAMENTO_SEGUNDOS);
            }
        } catch (InterruptedException interrompidoNoEncerramento) {
            // A consequência é a MESMA do teto estourado logo acima — a derrubada em curso pode não
            // completar —, então o rastro tem de ser o mesmo. Este era o único caminho mudo da fatia.
            log.warn("a espera pelo encerramento da passada foi interrompida antes dos {}s —"
                            + " seguindo com a parada; trabalho de derrubada em curso pode não completar",
                    PRAZO_DE_ENCERRAMENTO_SEGUNDOS);
            Thread.currentThread().interrupt();
        }
    }
}
