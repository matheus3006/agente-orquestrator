package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O CINTO estendido ao CORPO do {@code execute} (Fatia I, peça 4 — a FONTE mais larga).
 *
 * <p><b>O produtor que o desenho não tinha enumerado.</b> O {@code execute} chamava
 * {@code runToOutcome} <b>sem try/catch</b>, e o {@code runToOutcome} não tem cinto abrangente
 * depois do {@code issues.checkout}. Qualquer exceção que escape dali vira
 * {@code RunOutcome.failure} lá no {@code dispatchOnce} e o Run termina <b>{@code FAILED} com o
 * lock carimbado, sem que o settle tenha sido tentado</b> — lock órfão, permanente e silencioso.
 * Sítios reais entre o checkout e o settle: {@code store.recordStep}
 * ({@code findById(...).orElseThrow()}), {@code contextIntelligence.inspect}, {@code recordCosts},
 * {@code agentSessions.record} e, já dentro do {@code execute}, {@code previews.captureIfPublished}
 * e {@code serverManifests.registerIfDeclared}.
 *
 * <p><b>O gatilho aqui não é artifício.</b> Um Run cuja linha em {@code heartbeat_run} não existe
 * faz o primeiro {@code recordStep} pós-checkout descer em {@code orElseThrow()} — é o MESMO estado
 * alcançável que o javadoc do {@code semDerrubarOSettle} já nomeia (run apagado pelo reaper de
 * linhas, company removida, banco fora), e {@code issue.execution_lock_run_id} não tem FK, então o
 * checkout de fato acontece antes do tombo.
 *
 * <p><b>Rede × fonte.</b> O {@code LockOrfaoReaper} colheria esta Issue no tick seguinte — mas a
 * garantia inteira passaria a depender de ele rodar, e ele roda na {@code scheduling-1}, a thread
 * única. O cinto é a FONTE; o reaper é a REDE.
 */
@SpringBootTest
@ActiveProfiles("test")
class CorpoDoRunSemDerrubarOSettleTest {

    @Autowired
    private AdapterRunExecutor executor;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    @Test
    void excecaoEntreOcheckoutEOsettleNaoDeixaAIssueCarimbadaComRunMorto() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo oi", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa que não pode morrer de pé", null).getId();

        // Run SEM linha em heartbeat_run: o checkout passa (sem FK), e o primeiro recordStep
        // depois dele levanta — exatamente entre o checkout e o settle.
        UUID runSemLinha = UUID.randomUUID();

        assertThatCode(() -> executor.execute(new RunContext(companyId, agentId, runSemLinha, issueId)))
                .as("se isto sobe, o dispatchOnce marca o run FAILED e o settle nunca foi tentado")
                .doesNotThrowAnyException();

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();
    }
}
