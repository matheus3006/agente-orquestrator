package com.orquestrador.execution;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * O REAPER da coluna {@code execution_lock_run_id} (Fatia I, peça 2 — a REDE).
 *
 * <p>A rede existe porque nem todo produtor de lock órfão tem conserto na fonte: o drain em lote do
 * hard-stop cancela por UPDATE em massa (sem {@code runId} individual na mão), e o próprio
 * {@code settleAfterRun} pode LANÇAR no meio (banco fora) — aí o run pousa {@code FAILED} com o
 * carimbo de pé. Uma varredura não distingue órfão novo de órfão antigo, então ela cobre também o
 * passivo já acumulado.
 *
 * <p><b>Os controles negativos são obrigatórios, não decoração.</b> Um reaper que colhe "tudo"
 * passaria em todos os casos positivos e arrancaria Issues de runs vivos. Por isso as duas metades
 * andam juntas — e, onde dá, na MESMA varredura: colhe o órfão E deixa em paz quem tem dono.
 *
 * <p><b>Emendado pela Fatia I-bis.</b> Três coisas que este teste afirmava deixaram de valer:
 * {@code INTERRUPTED} agora É colhido (decisão do dono), lock apontando para Run <b>sem linha</b>
 * é órfão (era o ponto cego — a rede não o via), e a varredura tem TETO por passada.
 */
@SpringBootTest
@ActiveProfiles("test")
class LockOrfaoReaperTest {

    /** O teto que a varredura deve respeitar por passada. Literal do teste, de propósito. */
    private static final int TETO_ESPERADO = 50;

    /** Runs que AINDA TÊM FUTURO — a Issue segue deles. Literal do teste, de propósito. */
    private static final Set<RunStatus> DEIXA_EM_PAZ =
            EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING);

    /** Runs SEM futuro — a Issue não é de ninguém, e a rede a devolve ao board. */
    private static final Set<RunStatus> COLHE =
            EnumSet.of(RunStatus.CANCELLED, RunStatus.FAILED, RunStatus.SUCCEEDED, RunStatus.INTERRUPTED);

    @Autowired
    private LockOrfaoReaper reaper;
    @Autowired
    private ActivityLogRepository activityLogs;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    /** O UPDATE guardado cru: monta o lock órfão que o serviço, desde a I-ter, se recusa a criar. */
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void colheIssueCarimbadaPorRunEmStatusTerminal() {
        Carimbada cancelado = carimbar(RunStatus.CANCELLED);
        Carimbada falhado = carimbar(RunStatus.FAILED);
        Carimbada sucedido = carimbar(RunStatus.SUCCEEDED);

        reaper.reapOnce();

        assertThat(recarregar(cancelado).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(falhado).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(sucedido).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(cancelado).getExecutionLockRunId()).isNull();
    }

    /**
     * <b>O PONTO CEGO da rede da Fatia I</b> (Fatia I-bis, item 1): o lock aponta para um Run que
     * <b>não tem linha</b> em {@code heartbeat_run}.
     *
     * <p>A query definia órfã por {@code exists(run em status terminal)} — sem linha, o
     * {@code exists} é falso e a Issue <b>nunca entrava na lista</b>. A rede era fail-CLOSED
     * exatamente onde o dano é máximo: nenhum status a resgata, porque não há status.
     *
     * <p><b>O estado ERA alcançável em UMA chamada, e não é mais</b> (Fatia I-ter). Até ela,
     * {@code IssueService.checkout} fazia {@code effectiveRun = runId != null ? runId : randomUUID()}
     * e o {@code tryCheckout} não conferia existência: qualquer cliente que chamasse
     * {@code /checkout} sem corpo produzia este estado <b>por design</b>. Essa fonte está fechada —
     * o serviço agora exige um {@code runId} com procedência.
     *
     * <p><b>O teste continua, e o veredito não mudou.</b> {@code execution_lock_run_id} segue sem
     * FK, e o estado segue chegando ao banco por vias que não passam pelo balcão: o passivo já
     * acumulado, o drain em lote do hard-stop, um {@code settleAfterRun} que lança no meio, um
     * crash entre o CAS e o settle. <b>Fechar a fonte não aposenta a rede</b> — só tira dela o
     * produtor mais fácil.
     */
    @Test
    void colheIssueCujoRunNaoTemLINHA_oPontoCegoDaRede() {
        Carimbada semLinhaDeRun = carimbarComRunSemLinha();

        reaper.reapOnce();

        assertThat(recarregar(semLinhaDeRun).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(recarregar(semLinhaDeRun).getExecutionLockRunId()).isNull();
    }

    /**
     * <b>A CLASSIFICAÇÃO COMPLETA, sem default silencioso</b> (Fatia I-bis, item 4).
     *
     * <p>A Fatia I argumentou por escrito a exclusão de {@code INTERRUPTED} e <b>não argumentou</b>
     * a de {@code QUEUED}/{@code RECOVERING} — elas simplesmente não estavam na lista. Uma lista
     * sem critério é uma lista que a próxima pessoa amplia por analogia errada.
     *
     * <p><b>O critério, agora, é UM só:</b> o Run <b>ainda tem futuro</b>? {@code QUEUED} (o
     * {@code requeueExpired} devolve o Run à fila COM o lock de propósito — é o mesmo Run que
     * volta a rodar), {@code RUNNING} (trabalho em voo) e {@code RECOVERING} (reservado ao
     * auto-retry da ADR-0011) têm. Os demais não têm, e por isso são colhidos —
     * {@code INTERRUPTED} inclusive, por decisão do dono.
     *
     * <p><b>As duas tabelas abaixo são LITERAIS do teste</b>, não lidas da constante de produção:
     * um teste que recalcula a resposta pelo mesmo caminho do código passa por construção. E a
     * primeira asserção é a trava anti-default: {@code RunStatus} novo <b>quebra este teste</b> até
     * alguém decidir de que lado ele cai.
     */
    @Test
    void todoStatusDeRunTemLADO_eOreaperConcordaComACLASSIFICACAO() {
        EnumSet<RunStatus> classificados = EnumSet.copyOf(DEIXA_EM_PAZ);
        classificados.addAll(COLHE);
        assertThat(classificados)
                .as("RunStatus novo sem lado: decida se o reaper o colhe — nada de default silencioso")
                .isEqualTo(EnumSet.allOf(RunStatus.class));

        Map<RunStatus, Carimbada> porStatus = new EnumMap<>(RunStatus.class);
        for (RunStatus status : RunStatus.values()) {
            porStatus.put(status, carimbar(status));
        }

        reaper.reapOnce();

        assertSoftly(softly -> porStatus.forEach((status, carimbada) -> softly
                .assertThat(recarregar(carimbada).getStatus())
                .as("issue carimbada por run %s", status)
                .isEqualTo(COLHE.contains(status) ? IssueStatus.BLOCKED : IssueStatus.IN_PROGRESS)));
    }

    /**
     * O CONTROLE NEGATIVO com a segunda metade: além de não mudar o status, a varredura <b>não
     * toca o lock</b> da Issue que segue de um Run com futuro. Um reaper que colhesse "tudo"
     * passaria nos testes positivos e arrancaria Issues de runs vivos.
     *
     * <p>{@code QUEUED} é o caso mais afiado, não um enfeite: o {@code requeueExpired} devolve o
     * Run à fila <b>mantendo o carimbo de propósito</b>, porque é o MESMO Run que volta a rodar.
     * Colhê-lo arrancaria a Issue de trabalho que ia continuar.
     */
    @Test
    void naoColheNemDESTRAVAaIssueDeRunQueAindaTemFuturo() {
        Carimbada rodando = carimbar(RunStatus.RUNNING);
        Carimbada naFila = carimbar(RunStatus.QUEUED);

        reaper.reapOnce();

        assertSoftly(softly -> {
            softly.assertThat(recarregar(naFila).getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
            softly.assertThat(recarregar(naFila).getExecutionLockRunId()).isEqualTo(naFila.runId());
            softly.assertThat(recarregar(rodando).getStatus()).isEqualTo(IssueStatus.IN_PROGRESS);
            softly.assertThat(recarregar(rodando).getExecutionLockRunId()).isEqualTo(rodando.runId());
        });
    }

    /**
     * <b>A CERCA cross-tenant do sub-{@code exists}</b> (Fatia I-bis, item 8).
     *
     * <p>A ESCRITA sempre foi sólida — {@code tryBlock} tem {@code company_id}, então nunca houve
     * escrita cross-tenant. O furo era de OUTRA natureza: o sub-{@code exists} casava só por
     * {@code r.id}, então o <b>ciclo de vida do Run da company X</b> decidia se a Issue da company
     * Y era colhida e QUANDO — um canal de estado/timing atravessando o tenant. Enquanto o run de
     * X estivesse vivo, a Issue de Y ficava refém dele; quando X terminasse, Y era colhida.
     *
     * <p><b>O caminho ERA real, e a Fatia I-ter o fechou na fonte:</b> o {@code POST /checkout} lia
     * o {@code runId} do corpo sem conferi-lo contra a company, então um membro de Y carimbava a
     * Issue dele com o {@code runId} de X. Hoje o serviço exige procedência e recusa. A cerca da
     * varredura continua valendo para o estado que já esteja no banco: um lock que não nomeia Run
     * nenhum <i>de Y</i> é órfão na hora, independente do que X faça.
     *
     * <p>As duas metades na MESMA varredura: colhe a refém E deixa em paz a Issue da company que
     * de fato é dona do Run.
     */
    @Test
    void naoDeixaIssueREFEMdoCicloDeVidaDeRunDeOUTRAcompany() {
        Carimbada donoLegitimo = carimbar(RunStatus.RUNNING);

        UUID companyY = companies.create("Y", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentY = agents.save(new Agent(UUID.randomUUID(), companyY, "AY", "worker", null)).getId();
        UUID issueY = issues.create(companyY, "refem do run alheio", null).getId();
        // Pelo UPDATE guardado: desde a Fatia I-ter o /checkout recusa runId de outra company, então
        // o caminho descrito no parágrafo acima ("um membro de Y carimba com o runId de X") está
        // FECHADO NA FONTE. A rede segue provando que, se o estado existir no banco por qualquer
        // outra via, a Issue de Y não fica refém do ciclo de vida do Run de X.
        carimbarCru(issueY, companyY, agentY, donoLegitimo.runId());

        reaper.reapOnce();

        assertSoftly(softly -> {
            softly.assertThat(issues.get(companyY, issueY).getStatus())
                    .as("a Issue de Y não é de Run nenhum DE Y — órfã, e não refém do ciclo de X")
                    .isEqualTo(IssueStatus.BLOCKED);
            softly.assertThat(recarregar(donoLegitimo).getStatus())
                    .as("a Issue da company DONA do run vivo segue intacta")
                    .isEqualTo(IssueStatus.IN_PROGRESS);
        });
    }

    /**
     * <b>O TETO por passada</b> (Fatia I-bis, item 7).
     *
     * <p>A query não tinha {@code Top} nem {@code Pageable} e a classe declara cobrir <b>"o passivo
     * já acumulado"</b> — ou seja, o maior N possível — na {@code scheduling-1}, a thread ÚNICA
     * que a ADR-0050 mostrou ser compartilhada pelos 6 {@code @Scheduled}. O
     * {@code dispatchOnce} limita a 50 e o {@code RowRetentionReaper} chunkifica; esta varredura
     * era a única sem teto.
     *
     * <p>O teto não perde trabalho: o excedente é colhido na passada seguinte, e a ordem é a do
     * {@code updated_at} mais antigo — o passivo mais velho sai primeiro, sem inanição.
     */
    @Test
    void umaPassadaRespeitaOtetoEoExcedenteFicaParaAproxima() {
        List<Carimbada> orfas = carimbarLote(TETO_ESPERADO + 1);

        int primeira = reaper.reapOnce();
        long sobraDepoisDaPrimeira = aindaEmProgresso(orfas);
        reaper.reapOnce();
        long sobraDepoisDaSegunda = aindaEmProgresso(orfas);

        assertSoftly(softly -> {
            softly.assertThat(primeira)
                    .as("uma passada não pode varrer N ILIMITADO na thread única do scheduler")
                    .isEqualTo(TETO_ESPERADO);
            softly.assertThat(sobraDepoisDaPrimeira)
                    .as("o excedente do teto sobra de propósito")
                    .isGreaterThanOrEqualTo(1);
            softly.assertThat(sobraDepoisDaSegunda)
                    .as("e a passada seguinte o pega — teto não é trabalho perdido")
                    .isZero();
        });
    }

    /**
     * <b>O RASTRO não pode mentir sobre o ATOR</b> (Fatia I-bis, item 6).
     *
     * <p>O {@code settleAfterRun} grava {@code ActorType.AGENT} com o {@code assignee} da Issue —
     * correto quando quem liquida é o run do agente, <b>errado</b> quando quem liquida é uma
     * varredura de sistema que o agente não pediu nem soube. O precedente da casa é o
     * {@code WorkspaceReaper.recordCollected}, que grava {@code ActorType.SYSTEM}.
     *
     * <p>O agravante que torna isto mais que cosmético: o {@code reapOnce} só loga um INFO
     * agregado ("N issues devolvidas"), então esta entrada <b>é a única evidência por-issue</b> de
     * que a colheita aconteceu. Mal rotulada, ela imputa ao agente um ato que não foi dele — e é
     * exatamente onde alguém vai olhar para entender por que a Issue saiu de {@code IN_PROGRESS}.
     */
    @Test
    void oRastroDaVarreduraEhDoSISTEMA_naoDoAgenteAssignee() {
        Carimbada orfa = carimbar(RunStatus.FAILED);

        reaper.reapOnce();

        ActivityLog rastro = activityLogs
                .findFirstByCompanyIdAndEntityIdAndActionOrderByCreatedAtDesc(
                        orfa.companyId(), orfa.issueId(), "ISSUE_BLOCKED")
                .orElseThrow(() -> new AssertionError("a colheita não deixou rastro por-issue"));

        assertSoftly(softly -> {
            softly.assertThat(rastro.getActorType())
                    .as("varredura de sistema não é ato do agente")
                    .isEqualTo(ActorType.SYSTEM.name());
            softly.assertThat(rastro.getActorId())
                    .as("SYSTEM não tem ator individual (precedente: WorkspaceReaper)")
                    .isNull();
            softly.assertThat(rastro.getDetail())
                    .as("o runId morto é o que explica a colheita")
                    .contains(orfa.runId().toString());
        });
    }

    private record Carimbada(UUID companyId, UUID issueId, UUID runId) {
    }

    /** Uma Issue IN_PROGRESS carimbada com um run no status dado — o estado do órfão. */
    private Carimbada carimbar(RunStatus status) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa " + status, null).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, status)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        return new Carimbada(companyId, issueId, runId);
    }

    /**
     * O órfão do PONTO CEGO: a Issue é carimbada com um {@code runId} que <b>não existe</b> em
     * {@code heartbeat_run}.
     *
     * <p><b>O carimbo vem do UPDATE guardado, não do {@code IssueService}</b> (mudou na Fatia
     * I-ter). Até ela, este fixture dizia — com razão — que "o {@code /checkout} sem corpo produz
     * este estado sozinho"; era a FONTE que a I-ter fechou. Hoje o serviço exige procedência e
     * <b>recusa</b> criar este lock, então montá-lo pelo {@code tryCheckout} é o único jeito
     * honesto: é exatamente o que sobra no banco vindo do <b>passivo já acumulado</b> e dos
     * produtores que não passam pelo balcão (drain em lote, settle que lança, crash entre o CAS e o
     * settle). A rede continua existindo por causa deles — fonte e rede nunca se substituíram.
     */
    private Carimbada carimbarComRunSemLinha() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa com run fantasma", null).getId();
        UUID runSemLinha = UUID.randomUUID();
        carimbarCru(issueId, companyId, agentId, runSemLinha);
        return new Carimbada(companyId, issueId, runSemLinha);
    }

    /** {@code quantos} órfãos na MESMA company — o passivo acumulado, barato de montar. */
    private List<Carimbada> carimbarLote(int quantos) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        List<Carimbada> lote = new ArrayList<>();
        for (int i = 0; i < quantos; i++) {
            UUID issueId = issues.create(companyId, "passivo " + i, null).getId();
            UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                    RunStatus.FAILED)).getId();
            issues.checkout(companyId, issueId, agentId, runId);
            lote.add(new Carimbada(companyId, issueId, runId));
        }
        return lote;
    }

    private long aindaEmProgresso(List<Carimbada> lote) {
        return lote.stream().filter(c -> recarregar(c).getStatus() == IssueStatus.IN_PROGRESS).count();
    }

    private Issue recarregar(Carimbada carimbada) {
        return issues.get(carimbada.companyId(), carimbada.issueId());
    }

    /**
     * O UPDATE guardado precisa de transação (o {@code @Modifying} faz flush/clear). Mesmo recurso
     * que o {@code RowRetentionReaperTest} e o {@code ParecerGateTest} já usam para escrever cru.
     */
    private void carimbarCru(UUID issueId, UUID companyId, UUID agentId, UUID runId) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> issueRepo.tryCheckout(issueId, companyId, agentId, runId));
    }
}
