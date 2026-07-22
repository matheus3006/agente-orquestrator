package com.orquestrador.work.issue;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.LockOrfaoReaper;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.review.ReviewDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * O procedimento manual de destrave de Issue travada tem DOIS RAMOS —
 * {@code docs/ficha-seguranca-complete.md}.
 *
 * <p>Este teste existe porque a ficha é <b>instrução operacional</b>: alguém vai executá-la às
 * cegas, num incidente, contra uma Issue de produção. A forma de 4 chamadas do grill descrevia só o
 * ramo de risco baixo e <b>quebrava justamente nas Issues que mais importam</b> — as de risco alto,
 * onde a 3ª chamada NÃO produz {@code DONE} e o {@code /reabrir} devolve 409. Uma ficha que erra o
 * 4º passo deixa a Issue em {@code PENDING_REVIEW} com um ciclo de review fantasma.
 *
 * <p>Os dois ramos convergem para {@code BLOCKED}, de onde o {@code unblock {REOPEN|CANCEL}} decide.
 */
@SpringBootTest
@ActiveProfiles("test")
class DestraveManualDosDoisRamosTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private LockOrfaoReaper lockOrfaoReaper;
    /** O UPDATE guardado cru: monta o lock sintético que o serviço, desde a I-ter, recusa criar. */
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void riscoBAIXO_aTerceiraChamadaPassaPorDoneEaQuartaEhOreabrir() {
        Travada travada = issueTravada(1);

        // 3ª chamada da ficha: POST /complete {runId} — libera o lock passando por DONE.
        issues.complete(travada.companyId(), travada.issueId(), travada.runMorto(), travada.agentId());
        assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus()).isEqualTo(IssueStatus.DONE);

        // 4ª chamada — OBRIGATÓRIA: sem ela o board mostra entrega que ninguém entregou.
        issues.reabrirEntregaAprovada(travada.companyId(), travada.issueId(),
                "destravada à mão", ActorType.BOARD, UUID.randomUUID());

        assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }

    @Test
    void riscoALTO_aTerceiraChamadaCaiEmPendingReviewEoReabrirDA409_aQuartaEhOreject() {
        Travada travada = issueTravada(5);

        // 3ª chamada: o settle intercepta em trySubmitForReview — que TAMBÉM casa, porque a órfã
        // é IN_PROGRESS. A Issue NÃO passa por DONE.
        issues.complete(travada.companyId(), travada.issueId(), travada.runMorto(), travada.agentId());
        assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus())
                .isEqualTo(IssueStatus.PENDING_REVIEW);

        // A 4ª chamada do ramo de risco baixo QUEBRA aqui — o guard do reabrir é status='DONE'.
        assertThatThrownBy(() -> issues.reabrirEntregaAprovada(travada.companyId(), travada.issueId(),
                "destravada à mão", ActorType.BOARD, UUID.randomUUID()))
                .as("é por isso que a ficha precisa dos dois ramos")
                .isInstanceOf(ConflictException.class);

        // A 4ª chamada CERTA deste ramo: o REJECT do board (PENDING_REVIEW -> BLOCKED).
        issues.review(travada.companyId(), travada.issueId(), ReviewDecision.REJECT,
                "destravada à mão", null, ActorType.BOARD, UUID.randomUUID());

        assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }

    /**
     * <b>O Ramo B estava provado só para a classe que o reaper já colhia sozinho — e QUEBRADO na
     * única para a qual ele ainda era necessário</b> (Fatia I-bis, item 3).
     *
     * <p>O {@code issueTravada} semeia um run <b>REAL</b>. Mas a classe de órfã que mais precisava
     * do destrave manual é a do {@code runId} <b>SINTÉTICO</b> (sem linha em {@code heartbeat_run}).
     * Na Fatia I-bis a 3ª chamada da ficha falhava com {@code ConflictException} lá no fim do funil
     * — o {@code trySubmitForReview} casava, o {@code existsByIdAndCompanyId} não, e o rollback
     * TOTAL desfazia o destrave — e isso valia <b>só</b> para {@code riskLevel} ≥ limiar.
     *
     * <p><b>A Fatia I-ter moveu a recusa para mais cedo e a ALARGOU — é um limite que este desenho
     * CRIA, e está escrito aqui em vez de na prosa.</b> A cerca de procedência roda antes do funil,
     * então a 3ª chamada agora morre com {@code ForbiddenException} — e para <b>qualquer</b>
     * {@code riskLevel}, porque um runId sem linha nunca tem procedência. Ou seja: o destrave
     * manual, que já não funcionava para esta classe no ramo de risco alto, passou a não funcionar
     * nela em ramo nenhum. É troca consciente: o preço de fechar a fonte é que o operador perde uma
     * saída que, para esta classe, <b>nunca</b> chegou a funcionar por inteiro.
     *
     * <p><b>O que fecha o buraco não é a ficha — é a REDE</b>, que colhe esta classe sozinha em ≤5s.
     */
    @Test
    void riscoALTOcomRunSINTETICO_aFichaNAOdestrava_masARedeCOLHE() {
        Travada travada = issueTravadaComRunSintetico(5);

        assertThatThrownBy(() -> issues.complete(travada.companyId(), travada.issueId(),
                travada.runMorto(), travada.agentId()))
                .as("a cerca de procedencia recusa ANTES do funil: runId sem linha nao e de ninguem")
                .isInstanceOf(ForbiddenException.class);

        // O alargamento, medido e não só afirmado: no ramo de risco BAIXO a ficha destravava esta
        // classe até a I-bis (passava por DONE). Agora ela também para na cerca.
        Travada baixo = issueTravadaComRunSintetico(1);
        assertThatThrownBy(() -> issues.complete(baixo.companyId(), baixo.issueId(),
                baixo.runMorto(), baixo.agentId()))
                .as("o ramo de risco baixo, que ANTES destravava esta classe, agora tambem para")
                .isInstanceOf(ForbiddenException.class);

        assertSoftly(softly -> {
            softly.assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus())
                    .as("recusada na porta, a chamada não move nada: a Issue NÃO saiu de IN_PROGRESS")
                    .isEqualTo(IssueStatus.IN_PROGRESS);
            softly.assertThat(issues.get(travada.companyId(), travada.issueId()).getExecutionLockRunId())
                    .as("e o lock continua de pé — a ficha não resolveu esta classe")
                    .isEqualTo(travada.runMorto());
        });

        lockOrfaoReaper.reapOnce();

        assertSoftly(softly -> {
            softly.assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus())
                    .as("a REDE colhe o que a ficha não destrava")
                    .isEqualTo(IssueStatus.BLOCKED);
            softly.assertThat(issues.get(travada.companyId(), travada.issueId()).getExecutionLockRunId())
                    .as("e libera o lock, sem 4ª chamada nenhuma")
                    .isNull();
        });
    }

    /** O {@code agentId} entra no registro porque a 3ª chamada da ficha agora exige DIZER quem age. */
    private record Travada(UUID companyId, UUID issueId, UUID runMorto, UUID agentId) {
    }

    /**
     * A órfã do PONTO CEGO com risco ALTO: {@code runId} sintético (sem linha em
     * {@code heartbeat_run}).
     *
     * <p>O carimbo vem do UPDATE guardado desde a Fatia I-ter: o {@code /checkout} deixou de
     * fabricar este lock, mas ele segue existindo no banco como passivo acumulado e por produtores
     * que não passam pelo balcão. É contra esse passivo que a ficha é executada.
     */
    private Travada issueTravadaComRunSintetico(int riskLevel) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "travada por run sintetico", null, riskLevel).getId();
        UUID runSintetico = UUID.randomUUID();
        carimbarCru(issueId, companyId, agentId, runSintetico);
        return new Travada(companyId, issueId, runSintetico, agentId);
    }

    /** Uma Issue IN_PROGRESS carimbada com um run CANCELLED — o estado que a ficha destrava. */
    private Travada issueTravada(int riskLevel) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "tarefa travada", null, riskLevel).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.CANCELLED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        return new Travada(companyId, issueId, runId, agentId);
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
