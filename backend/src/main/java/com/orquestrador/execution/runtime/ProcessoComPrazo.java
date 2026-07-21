package com.orquestrador.execution.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Um subprocesso com PRAZO — o idioma único do motor para esperar por processo (ADR-0050).
 *
 * <p><b>Por que a coleta roda em thread própria.</b> O jeito intuitivo de dar prazo a um processo é
 * trocar {@code waitFor()} por {@code waitFor(n, unit)}. <b>No molde desta casa isso não conserta
 * nada</b>, e isso foi MEDIDO, não suposto: os chamadores drenam a saída antes de esperar
 * ({@code readLine()} em laço, {@code readAllBytes()}), e um processo que vive para sempre segurando
 * o stdout aberto nunca produz o EOF que encerra o drain — a thread fica parada em
 * {@code FileInputStream.readBytes} e <b>o {@code waitFor} sequer é alcançado</b>. O prazo tem de
 * cercar a OPERAÇÃO INTEIRA (drenar + esperar), não a espera final; por isso a coleta do chamador
 * vem para cá e roda numa thread que o estouro pode abandonar.
 *
 * <p>O único site da casa que já tinha prazo — {@code LocalExecutionTarget.screenshotServer} — é
 * também o único que drenava fora da thread principal, e o comentário dele dizia exatamente por quê
 * ("o timeout do waitFor precisa poder disparar"). Este módulo generaliza aquele idioma.
 *
 * <p><b>Matar o processo do host nem sempre mata o trabalho.</b> Em Docker, {@code destroyForcibly()}
 * atinge o CLIENTE {@code docker run}, que não repassa sinal ao container (o mesmo motivo pelo qual
 * o interrupt da ADR-0040 endereça o container pelo NOME). Por isso a morte aceita uma
 * {@code escalarMorte}: quem sabe alcançar o trabalho de verdade — {@code docker rm -f} pelo nome,
 * ou os descendentes no LOCAL — passa a própria derrubada, e ela roda ANTES do golpe no cliente.
 * <b>Os DOIS caminhos de morte escalam, e nos dois a escalação COMPLETA</b> — estouro de prazo e
 * interrupção. O de interrupção é o do shutdown da JVM, e era exatamente ali que a assimetria
 * deixava container órfão: primeiro porque não escalava (fechado no PR #56), depois porque escalava
 * com a flag de interrupção já reposta, o que matava a própria escalação no primeiro ponto de espera
 * (fechado aqui — ver o comentário do ramo de {@code InterruptedException} em {@link #aguardar}).
 */
final class ProcessoComPrazo {

    private static final Logger log = LoggerFactory.getLogger(ProcessoComPrazo.class);

    private ProcessoComPrazo() {
    }

    /** A coleta do chamador (drenar stdout/stderr e esperar) — roda na thread que o prazo abandona. */
    @FunctionalInterface
    interface Coleta<T> {
        T colher() throws Exception;
    }

    /**
     * O que a operação devolveu, ou o registro de que ela não coube no prazo. Estourou ⇒
     * {@link #colhido()} é {@code null} e {@link #motivo()} carrega a frase legível — o modo de
     * falha que estamos consertando era silêncio, então o estouro sempre tem texto.
     */
    record Resultado<T>(T colhido, boolean estourou, String motivo) {

        boolean concluiu() {
            return !estourou;
        }
    }

    /** Sem escalação: o golpe no processo do host basta (nada sobrevive a ele). */
    static <T> Resultado<T> aguardar(String operacao, Process processo, Duration prazo, Coleta<T> coleta)
            throws Exception {
        return aguardar(operacao, processo, prazo, coleta, null);
    }

    /**
     * Roda {@code coleta} com teto de {@code prazo}. Coube: {@code Resultado} com o colhido. Não
     * coube: {@code escalarMorte} (se houver) → {@code destroyForcibly} → resultado de estouro com
     * motivo legível e WARN no log. A exceção da coleta sobe como veio (os chamadores já a
     * traduzem em falha legível), e a escalação nunca mascara o estouro.
     */
    static <T> Resultado<T> aguardar(String operacao, Process processo, Duration prazo,
                                     Coleta<T> coleta, Runnable escalarMorte) throws Exception {
        FutureTask<T> tarefa = new FutureTask<>(coleta::colher);
        Thread coletor = new Thread(tarefa, "prazo-" + operacao);
        // Daemon: uma coleta que ficou presa num descritor que nem o kill soltou não pode ser o que
        // impede a JVM de morrer.
        coletor.setDaemon(true);
        coletor.start();
        try {
            return new Resultado<>(tarefa.get(prazo.toMillis(), TimeUnit.MILLISECONDS), false, null);
        } catch (TimeoutException estouro) {
            String motivo = "'" + operacao + "' excedeu o prazo de " + prazo + " — processo derrubado";
            log.warn("prazo estourado: {}", motivo);
            matar(operacao, processo, escalarMorte);
            return new Resultado<>(null, true, motivo);
        } catch (InterruptedException interrompido) {
            // Derruba o processo e SÓ ENTÃO repõe a flag, antes de propagar. A derrubada é a MESMA
            // do estouro — escalação inclusive. Este é o ramo do shutdown da JVM
            // (`DispatchWorker.close` → `shutdownNow` → `interrupt`), e sem escalar aqui o golpe
            // atingiria só o cliente `docker run`: a aplicação desceria deixando o container do
            // agente órfão, vivo e invisível. Assimetria entre dois caminhos de morte é dívida.
            //
            // A ORDEM É O CONSERTO, não detalhe de estilo. O `awaitDone` do FutureTask CONSOME a
            // flag ao lançar, e repô-la aqui em cima matava a própria escalação: `docker rm -f` roda
            // dentro de um `aguardar` ANINHADO, na MESMA thread (DockerExecutionTarget.interrupt →
            // exec → aguardar), e com a flag posta o `tarefa.get` desse aninhado lança na hora —
            // o cliente do `rm -f` levava `destroyForcibly` e o container seguia órfão. Escalar
            // com a flag limpa é o que faz a derrubada CONCLUIR; o ramo de estouro acima sempre
            // funcionou por não ter flag nenhuma para repor. Restaurar depois preserva o contrato
            // de nunca engolir a interrupção: quem recebe a exceção recebe a flag junto.
            matar(operacao, processo, escalarMorte);
            Thread.currentThread().interrupt();
            throw interrompido;
        } catch (java.util.concurrent.ExecutionException falhaDaColeta) {
            Throwable causa = falhaDaColeta.getCause();
            if (causa instanceof Exception excecao) {
                throw excecao;
            }
            throw falhaDaColeta;
        }
    }

    /**
     * A escalação primeiro (é ela que alcança container/descendentes), o golpe no cliente depois —
     * e o golpe acontece MESMO SE a escalação explodir, senão uma falha na derrubada do container
     * deixaria a thread do chamador presa, que é o dano original.
     */
    private static void matar(String operacao, Process processo, Runnable escalarMorte) {
        if (escalarMorte != null) {
            try {
                escalarMorte.run();
            } catch (RuntimeException falhaAoEscalar) {
                log.warn("escalação da derrubada de '{}' falhou ({}) — seguindo para o golpe no"
                        + " processo do host", operacao, falhaAoEscalar.toString());
            }
        }
        processo.destroyForcibly();
    }
}
