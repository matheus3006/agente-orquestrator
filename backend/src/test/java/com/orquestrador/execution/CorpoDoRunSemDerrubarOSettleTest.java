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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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
 * <p><b>O gatilho aqui não é artifício</b> — mas MUDOU de lugar na Fatia I-ter. Um Run cuja linha
 * em {@code heartbeat_run} não existe é o MESMO estado alcançável que o javadoc do
 * {@code semDerrubarOSettle} já nomeia (run apagado pelo reaper de linhas, company removida, banco
 * fora). Até a I-ter ele derrubava o primeiro {@code recordStep} <b>depois</b> do checkout, porque
 * o checkout passava (sem FK, sem conferência). Hoje ele é recusado <b>pelo próprio checkout</b>
 * (cerca de procedência), e a exceção nasce uma linha antes — dentro do mesmo
 * {@code runToOutcome}, sob o mesmo cinto. Ver o javadoc do teste para o que isso muda no
 * pós-estado.
 *
 * <p><b>Rede × fonte — e a frase que esta classe afirmava sem ser verdade.</b> Até a Fatia I-bis
 * este parágrafo dizia que <i>"o {@code LockOrfaoReaper} colheria esta Issue no tick seguinte"</i>.
 * Era <b>falso exatamente no cenário que o teste monta</b>: o {@code runSemLinha} abaixo não tem
 * linha em {@code heartbeat_run}, e a query da rede pedia {@code exists(run em status terminal)} —
 * sem linha, a Issue nunca entrava na lista. A fonte cobria o caso e a rede declarada não cobria;
 * a frase descrevia uma rede que não existia para este órfão. Com o ponto cego fechado
 * (a query pergunta se existe Run <b>com futuro</b> segurando o lock), a afirmação passou a valer
 * — e {@code LockOrfaoReaperTest.colheIssueCujoRunNaoTemLINHA_oPontoCegoDaRede} é quem a sustenta.
 *
 * <p>A garantia, ainda assim, não deve <b>depender</b> do reaper: ele roda na {@code scheduling-1},
 * a thread única. O cinto é a FONTE (liquida na hora, com o motivo); o reaper é a REDE.
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

    /**
     * <b>O gatilho mudou na Fatia I-ter, e o resultado ficou MAIS forte — leia antes de "consertar"
     * a asserção de volta para {@code BLOCKED}.</b>
     *
     * <p>Até a I-ter o Run sem linha <b>conseguia carimbar</b> a Issue (o checkout não conferia
     * existência, e {@code execution_lock_run_id} não tem FK); o tombo vinha logo depois, no
     * {@code recordStep}, e o que este teste provava é que o cinto ainda liquidava a Issue em
     * {@code BLOCKED} em vez de deixá-la carimbada por um run morto.
     *
     * <p>Agora o {@code issues.checkout} <b>recusa</b> o run sem linha (cerca de procedência), e
     * essa recusa é uma {@code RuntimeException} lançada <b>dentro</b> do {@code runToOutcome} —
     * ou seja, ela passa exatamente pelo cinto que esta classe existe para provar. O cinto continua
     * sendo exercido; o que muda é o <b>destino</b>: a Issue nunca chegou a ser carimbada, então
     * não há nada para estacionar, e pousá-la em {@code BLOCKED} seria bloquear uma Issue que run
     * nenhum jamais deteve — precisamente o que o guard do {@code tryBlock} existe para impedir.
     *
     * <p><b>O invariante do nome deste teste — "não deixa a Issue carimbada com run morto" — passa
     * a ser satisfeito da forma mais forte possível: ela não é carimbada de jeito nenhum.</b>
     *
     * <p>⚠️ <b>Limite honesto:</b> o pós-estado {@code BLOCKED} (exceção depois de um checkout
     * BEM-SUCEDIDO) perdeu sua única reprodução, porque o gatilho que a produzia — run sem linha —
     * deixou de passar pelo checkout. O cinto segue coberto aqui na direção "não sobe"; a direção
     * "liquida o que já estava carimbado" só é exercida hoje pelo {@code LockOrfaoReaper}.
     */
    @Test
    void excecaoEntreOcheckoutEOsettleNaoDeixaAIssueCarimbadaComRunMorto() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo oi", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa que não pode morrer de pé", null).getId();

        // Run SEM linha em heartbeat_run — o mesmo estado alcançável que o javadoc nomeia (run
        // apagado pelo reaper de linhas, company removida, banco fora).
        UUID runSemLinha = UUID.randomUUID();

        AtomicReference<RunOutcome> desfecho = new AtomicReference<>();
        assertThatCode(() -> desfecho.set(
                executor.execute(new RunContext(companyId, agentId, runSemLinha, issueId))))
                .as("se isto sobe, o dispatchOnce marca o run FAILED e o settle nunca foi tentado")
                .doesNotThrowAnyException();

        assertSoftly(softly -> {
            softly.assertThat(desfecho.get().succeeded())
                    .as("o cinto converte o tombo em desfecho, e o run termina FAILED com motivo")
                    .isFalse();
            softly.assertThat(issues.get(companyId, issueId).getExecutionLockRunId())
                    .as("O INVARIANTE DESTA CLASSE: nenhum lock de run morto sobra na Issue")
                    .isNull();
            softly.assertThat(issues.get(companyId, issueId).getStatus())
                    .as("e ela sequer saiu de OPEN — run que não pôde pegá-la não pode bloqueá-la")
                    .isEqualTo(IssueStatus.OPEN);
        });
    }
}
