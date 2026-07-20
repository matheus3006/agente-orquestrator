package com.orquestrador.execution.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Os PRAZOS dos subprocessos do motor (ADR-0050). Todo {@code exec()} do motor tem prazo; o que
 * varia é o tamanho, e ele varia por NATUREZA da chamada — um {@code docker volume ls} não merece
 * o mesmo teto que o run de um agente. Os quatro defaults abaixo são escolhidos, não herdados:
 *
 * <ul>
 *   <li><b>{@link #getComandoCurto()} 30s</b> — plano de CONTROLE do daemon ({@code volume ls/rm},
 *       {@code port}, {@code ps}, {@code logs}, {@code rm -f}, {@code network inspect/create}).
 *       Respondem em milissegundos com o daemon são; 30s é ~100× a latência normal, então só
 *       dispara com o daemon travado. É o mesmo tamanho do único prazo que já existia na casa.</li>
 *   <li><b>{@link #getWorkspace()} 5min</b> — leitura/listagem/escrita de workspace SOBEM UM
 *       CONTAINER e movem um tar da árvore inteira. Imagem fria + árvore grande passam de um
 *       minuto legitimamente; 5min é folga sobre o pior caso observado sem deixar de ser teto.</li>
 *   <li><b>{@link #getScreenshot()} 30s</b> — herdado do único site que já tinha prazo
 *       ({@code LocalExecutionTarget.screenshotServer}), agora valendo também para o gêmeo Docker
 *       da MESMA operação, que não tinha. Preserva o comportamento local ao byte.</li>
 *   <li><b>{@link #getRun()} 2h</b> — o run do agente. Este é TETO DURO, não prazo de resposta: um
 *       falso positivo aqui MATA TRABALHO BOM, que é falha pior que a que estamos consertando. 2h
 *       fica muito acima de qualquer run legítimo observado e ainda assim troca "para sempre" por
 *       "limitado e recuperável". O que impede que essas 2h travem o resto do motor não é o
 *       tamanho do prazo — é o {@code DispatchWorker} tirar o run da thread do scheduler.</li>
 * </ul>
 */
@Component
@ConfigurationProperties("orquestrador.execution.timeout")
public class PrazosDeExecucao {

    /** Os defaults de produção, para quem constrói um alvo na mão (testes, fixtures). */
    public static final PrazosDeExecucao PADRAO = new PrazosDeExecucao();

    private Duration comandoCurto = Duration.ofSeconds(30);
    private Duration workspace = Duration.ofMinutes(5);
    private Duration screenshot = Duration.ofSeconds(30);
    private Duration run = Duration.ofHours(2);

    public Duration getComandoCurto() { return comandoCurto; }
    public void setComandoCurto(Duration comandoCurto) { this.comandoCurto = comandoCurto; }
    public Duration getWorkspace() { return workspace; }
    public void setWorkspace(Duration workspace) { this.workspace = workspace; }
    public Duration getScreenshot() { return screenshot; }
    public void setScreenshot(Duration screenshot) { this.screenshot = screenshot; }
    public Duration getRun() { return run; }
    public void setRun(Duration run) { this.run = run; }

    /** Os mesmos prazos com um teto de run curto — o que um teste precisa para provar o estouro. */
    static PrazosDeExecucao comPrazos(Duration comandoCurto, Duration workspace,
                                      Duration screenshot, Duration run) {
        PrazosDeExecucao prazos = new PrazosDeExecucao();
        prazos.setComandoCurto(comandoCurto);
        prazos.setWorkspace(workspace);
        prazos.setScreenshot(screenshot);
        prazos.setRun(run);
        return prazos;
    }
}
