package com.orquestrador.execution;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
 * <p><b>O critério é UM só: o Run ainda tem futuro?</b> {@code QUEUED} (o {@code requeueExpired}
 * devolve o Run à fila COM o lock de propósito — é o mesmo Run que volta a rodar), {@code RUNNING}
 * (trabalho em voo) e {@code RECOVERING} (reservado ao auto-retry da ADR-0011) têm futuro, e a
 * Issue segue deles. Todo o resto não tem. Antes da Fatia I-bis a lista era
 * {@code CANCELLED}/{@code FAILED}/{@code SUCCEEDED} sem critério escrito: a exclusão de
 * {@code INTERRUPTED} era argumentada, a de {@code QUEUED}/{@code RECOVERING} não era argumentada
 * em lugar nenhum. Lista sem critério é lista que a próxima pessoa amplia por analogia errada.
 *
 * <p><b>{@code INTERRUPTED} está DENTRO</b> (decisão do dono, Fatia I-bis). O argumento que o
 * mantinha fora — "o kill é best-effort, o processo pode estar vivo" — <b>invocava liveness</b>, e
 * o parágrafo anterior a este acabou de mostrar que a segurança da colheita <b>não depende de
 * liveness nenhuma</b>: os dois argumentos não podiam estar certos ao mesmo tempo. E o recorte
 * também não fechava: um container que SOBREVIVE à escalação de morte pousa {@code FAILED} —
 * processo vivo, mesmíssimo perigo — e esse a rede já varria desde a Fatia I. Com ele dentro, o
 * produtor "crash entre o CAS {@code RUNNING → INTERRUPTED} e o settle" deixa de ser <b>o único
 * sem cura</b>.
 *
 * <p><b>Lock apontando para Run que NÃO TEM LINHA também é órfão</b> (Fatia I-bis, o ponto cego).
 * A query da Fatia I pedia {@code exists(run em status terminal)}: sem linha o {@code exists} é
 * falso e a Issue <b>nunca entrava na lista</b> — fail-CLOSED exatamente onde o dano é máximo,
 * porque nenhum status a resgata quando não há status. O estado é alcançável em UMA chamada, sem
 * hostilidade: {@code IssueService.checkout} fabrica {@code UUID.randomUUID()} quando o
 * {@code runId} não vem, e {@code execution_lock_run_id} não tem FK. Hoje a query pergunta o
 * inverso — <b>existe algum Run com futuro segurando este lock?</b> — e a ausência de linha
 * responde "não" naturalmente.
 *
 * <p><b>Limites que este desenho CRIA, nomeados e não escondidos.</b> (1) A varredura pode
 * estacionar em {@code BLOCKED} uma Issue cujo processo ainda está vivo e ia entregar — o caso do
 * {@code requeueExpired}, e agora também o do {@code INTERRUPTED} cujo kill não pegou. É custo do
 * desenho, não bug: troca um dano permanente e mudo por um {@code REOPEN} do board sobre uma Issue
 * visível, com {@code ISSUE_BLOCKED} e o {@code runId} no activity log. (2) Com {@code INTERRUPTED}
 * dentro, a janela entre o CAS do interrupt e o settle do próprio interrupt passa a ser
 * <b>corrida com a varredura</b>: se o reaper chegar primeiro, o settle tardio casa 0 linhas e a
 * Issue pousa em {@code BLOCKED} pelo motivo do reaper em vez do motivo do interrupt. Os dois
 * destinos são {@code BLOCKED}; o que muda é a frase no log. (3) O que a rede <b>não</b> alcança:
 * lock apontando para Run que tem futuro e nunca o exerce (um {@code QUEUED} que ninguém despacha).
 * Esse é o resíduo honesto — ver a §Emenda da ADR-0011.
 */
@Service
public class LockOrfaoReaper {

    private static final Logger log = LoggerFactory.getLogger(LockOrfaoReaper.class);

    /**
     * Os status de Run que a varredura trata como "não roda mais" — o COMPLEMENTO exato de
     * {@code QUEUED}/{@code RUNNING}/{@code RECOVERING}, os três que ainda têm futuro. Ver o
     * critério único no javadoc da classe; {@code LockOrfaoReaperTest} trava a classificação de
     * cada valor do enum, então status novo não cai num default silencioso.
     */
    private static final List<RunStatus> RUNS_QUE_NAO_RODAM_MAIS = List.of(
            RunStatus.CANCELLED, RunStatus.FAILED, RunStatus.SUCCEEDED, RunStatus.INTERRUPTED);

    /**
     * Teto de Issues por passada, na disciplina do {@code findTop50} do {@code dispatchOnce}: esta
     * varredura roda na {@code scheduling-1} (thread única, ADR-0050) e declara cobrir o passivo
     * ACUMULADO — o maior N possível. O excedente sai na passada seguinte, mais velho primeiro.
     */
    private static final int TETO_POR_PASSADA = 50;

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
     * <p>Best-effort <b>não-abortante, ponto</b> — na mesma disciplina dos outros reapers da casa:
     * uma Issue que exploda não pode levar as demais junto, nem parar a thread do scheduler, que é
     * compartilhada (ADR-0050). Até a Fatia I-bis a garantia valia só <b>por Issue</b>: a chamada da
     * varredura estava FORA do cinto e um throw dela subia inteiro. Este método não lança.
     */
    public int reapOnce() {
        List<Issue> orfas;
        try {
            orfas = issues.findComLockDeRunTerminal(IssueStatus.IN_PROGRESS, RUNS_QUE_NAO_RODAM_MAIS,
                    PageRequest.ofSize(TETO_POR_PASSADA));
        } catch (RuntimeException aoVarrer) {
            // A própria VARREDURA estava fora do cinto: o try/catch de baixo protege o laço, mas um
            // throw aqui subia inteiro. Nesta thread (scheduling-1, compartilhada pelos 6
            // @Scheduled) isso derruba o resto do tick — e a passada seguinte reencontra os mesmos
            // órfãos, então perder UMA passada custa um tick, não um órfão.
            log.warn("varredura do lock orfao falhou nesta passada: {}", aoVarrer.toString());
            return 0;
        }
        int colhidas = 0;
        for (Issue orfa : orfas) {
            UUID runMorto = orfa.getExecutionLockRunId();
            try {
                if (issueService.settleAfterRun(orfa.getCompanyId(), orfa.getId(), runMorto,
                        RunOutcome.failure(MOTIVO), ActorType.SYSTEM)
                        != com.orquestrador.work.issue.IssueSettlement.NOT_SETTLED) {
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
