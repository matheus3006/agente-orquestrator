package com.orquestrador.work.issue;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.execution.HeartbeatRun;
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

    private record Travada(UUID companyId, UUID issueId, UUID runMorto) {
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
