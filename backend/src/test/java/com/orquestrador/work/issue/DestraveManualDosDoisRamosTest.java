package com.orquestrador.work.issue;

import com.orquestrador.common.error.ConflictException;
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

    @Test
    void riscoBAIXO_aTerceiraChamadaPassaPorDoneEaQuartaEhOreabrir() {
        Travada travada = issueTravada(1);

        // 3ª chamada da ficha: POST /complete {runId} — libera o lock passando por DONE.
        issues.complete(travada.companyId(), travada.issueId(), travada.runMorto());
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
        issues.complete(travada.companyId(), travada.issueId(), travada.runMorto());
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
     * do destrave manual é a do {@code runId} <b>SINTÉTICO</b> (sem linha em {@code heartbeat_run})
     * — a que o {@code /checkout} sem corpo fabrica sozinho. Nela, com {@code riskLevel} ≥ limiar,
     * a 3ª chamada da ficha <b>não destrava nada</b>: o {@code trySubmitForReview} casa (a Issue é
     * {@code IN_PROGRESS}), o {@code existsByIdAndCompanyId} falha, e o {@code ConflictException}
     * dá <b>rollback TOTAL</b> — o UPDATE guardado é desfeito e a Issue volta a {@code IN_PROGRESS}
     * com o lock de pé. O operador executa o procedimento, recebe um 409 e a Issue segue travada.
     *
     * <p><b>O que fecha o buraco não é a ficha — é a REDE.</b> Com o ponto cego corrigido, esta
     * classe é colhida automaticamente, e o destrave manual deixa de ser necessário justamente onde
     * ele não funcionava. A ficha passa a dizer isso.
     */
    @Test
    void riscoALTOcomRunSINTETICO_aFichaNAOdestrava_masARedeCOLHE() {
        Travada travada = issueTravadaComRunSintetico();

        assertThatThrownBy(() -> issues.complete(travada.companyId(), travada.issueId(), travada.runMorto()))
                .as("review exige run real: 409 e rollback TOTAL do UPDATE guardado")
                .isInstanceOf(ConflictException.class);

        assertSoftly(softly -> {
            softly.assertThat(issues.get(travada.companyId(), travada.issueId()).getStatus())
                    .as("o rollback desfaz o destrave: a Issue NÃO saiu de IN_PROGRESS")
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

    private record Travada(UUID companyId, UUID issueId, UUID runMorto) {
    }

    /**
     * A órfã do PONTO CEGO com risco ALTO: {@code runId} sintético (sem linha em
     * {@code heartbeat_run}), exatamente o que o {@code /checkout} sem corpo produz.
     */
    private Travada issueTravadaComRunSintetico() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID issueId = issues.create(companyId, "travada por run sintetico", null, 5).getId();
        UUID runSintetico = UUID.randomUUID();
        issues.checkout(companyId, issueId, agentId, runSintetico);
        return new Travada(companyId, issueId, runSintetico);
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
        return new Travada(companyId, issueId, runId);
    }
}
