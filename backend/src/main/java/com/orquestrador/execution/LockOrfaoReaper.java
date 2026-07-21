package com.orquestrador.execution;

import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * <b>A REDE do lock órfão</b> (Fatia I, peça 2): colhe Issues {@code IN_PROGRESS} cujo
 * {@code execution_lock_run_id} aponta para um Run que <b>não roda mais</b>, liquidando-as em
 * {@code BLOCKED} — o park-for-human da ADR-0011, de onde o {@code unblock {REOPEN|CANCEL}} do board
 * já sabe tirá-las.
 *
 * <p><b>Por que a rede existe se a fonte foi consertada.</b> Nem todo produtor de órfão tem
 * {@code runId} na mão: o drain em lote do hard-stop ({@code BudgetService.drainScopeWorkToCheckpoint})
 * cancela por UPDATE em massa, e o próprio {@code settleAfterRun} pode <b>lançar</b> no meio (banco
 * fora) — aí o Run pousa {@code FAILED} com o carimbo de pé. A varredura ainda cobre o <b>passivo já
 * acumulado</b> (ela não distingue órfão novo de órfão antigo) e qualquer produtor futuro que
 * termine o Run sem liquidar. <b>Rede e fonte não se substituem:</b> o cinto do executor é a fonte,
 * isto é a rede.
 *
 * <p><b>Por que é seguro colher, sem supor nada sobre quem está vivo.</b> O argumento NÃO é "estes
 * status implicam processo morto" — não implicam: o {@code requeueExpired} devolve um Run a
 * {@code QUEUED} com o lock já carimbado e o processo ainda VIVO, e dali um {@code cancel} o leva a
 * {@code CANCELLED} com processo em voo. O argumento é que <b>o banco arbitra as duas ordens</b>:
 * {@code tryBlock} tem {@code where execution_lock_run_id = :runId and status = 'IN_PROGRESS'}.
 * Reaper primeiro ⇒ o settle tardio do zumbi casa 0 linhas e não desfaz nada. Zumbi primeiro ⇒ o
 * {@code tryBlock} do reaper casa 0 linhas. Uma das duas ordens acontece, e nenhuma das duas
 * corrompe a Issue.
 *
 * <p><b>{@code INTERRUPTED} fica FORA, de propósito.</b> Ele é escrito pelo {@code RunInterruptService}
 * <b>enquanto o processo pode estar vivo</b> — o kill é best-effort e {@code DockerExecutionTarget.interrupt}
 * devolve {@code false} quando o {@code exec} não sai 0. Colhê-lo arrancaria a Issue de um processo
 * que ainda vai escrever. A decisão de trazê-lo pertence ao grill do contrato do settle sob
 * autoridade revogada (D-POSSE-6), que ainda não fechou — e enquanto ele não fechar, o produtor
 * "crash entre o CAS do interrupt e o settle" segue <b>sem cura</b>.
 *
 * <p><b>Limite que este desenho CRIA, nomeado e não escondido:</b> a varredura pode estacionar em
 * {@code BLOCKED} uma Issue cujo processo ainda está vivo e ia entregar — o caso do
 * {@code requeueExpired} acima. É custo do desenho, não bug: o dano que ele troca (Issue impegável
 * <b>para sempre</b>, silenciosa) é permanente; este é um {@code REOPEN} do board sobre uma Issue
 * visível, com {@code ISSUE_BLOCKED} e o {@code runId} no activity log.
 */
@Service
public class LockOrfaoReaper {

    private static final Logger log = LoggerFactory.getLogger(LockOrfaoReaper.class);

    /**
     * Os status de Run que a varredura trata como "não roda mais". {@code INTERRUPTED} <b>não</b>
     * está aqui — ver o javadoc da classe; incluí-lo é decisão da D-POSSE-6, não deste arquivo.
     */
    private static final List<RunStatus> RUNS_QUE_NAO_RODAM_MAIS =
            List.of(RunStatus.CANCELLED, RunStatus.FAILED, RunStatus.SUCCEEDED);

    private static final String MOTIVO =
            "lock orfao colhido: o run que detinha esta issue nao roda mais (ADR-0011)";

    private final IssueRepository issues;
    private final IssueService issueService;

    public LockOrfaoReaper(IssueRepository issues, IssueService issueService) {
        this.issues = issues;
        this.issueService = issueService;
    }

    /**
     * Uma passada. Retorna quantas Issues foram efetivamente liquidadas — o {@code settleAfterRun}
     * devolve {@code NOT_SETTLED} quando o guard casa 0 linhas (alguém liquidou entre a leitura e a
     * escrita), e essas não contam.
     *
     * <p>Best-effort <b>não-abortante por Issue</b>, na mesma disciplina dos outros reapers da casa:
     * uma Issue que exploda não pode levar as demais junto — nem parar a thread do scheduler, que é
     * compartilhada (ADR-0050).
     */
    public int reapOnce() {
        List<Issue> orfas = issues.findComLockDeRunTerminal(IssueStatus.IN_PROGRESS, RUNS_QUE_NAO_RODAM_MAIS);
        int colhidas = 0;
        for (Issue orfa : orfas) {
            UUID runMorto = orfa.getExecutionLockRunId();
            try {
                if (issueService.settleAfterRun(orfa.getCompanyId(), orfa.getId(), runMorto,
                        RunOutcome.failure(MOTIVO)) != com.orquestrador.work.issue.IssueSettlement.NOT_SETTLED) {
                    colhidas++;
                }
            } catch (RuntimeException aoLiquidar) {
                log.warn("falha ao colher o lock orfao da issue {} (run morto {}): {}",
                        orfa.getId(), runMorto, aoLiquidar.toString());
            }
        }
        if (colhidas > 0) {
            log.info("lock orfao: {} issue(s) devolvida(s) ao board em BLOCKED", colhidas);
        }
        return colhidas;
    }
}
