package com.orquestrador.execution;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O CINTO DE ÚLTIMA INSTÂNCIA dos 4 gates de fim-de-run (`semDerrubarOSettle`), que o hardening do
 * PR #50 introduziu e <b>ninguém testou</b> — zero {@code GATE_DE_SAIDA_FALHOU} em {@code src/test}
 * até aqui (conferência dos PRs #51/#52, 🔵 marcado como o mais valioso dos menores).
 *
 * <p><b>Por que importa mais do que parece.</b> Os gates rodam dentro do {@code runToOutcome}, que
 * o {@code execute} chama ANTES do {@code settleAfterRun}. Uma exceção que suba dali não é "um gate
 * falhou": ela PULA o settle e deixa a Issue {@code IN_PROGRESS} com {@code execution_lock_run_id}
 * de um run morto, e o {@code tryCheckout} exige essa coluna null-ou-igual. <b>Emendado pela Fatia
 * I-bis:</b> este parágrafo dizia que <i>não existe reaper para ela</i> e que a Issue ficava
 * impegável PARA SEMPRE — o {@code LockOrfaoReaper} existe desde a Fatia I e, com o ponto cego
 * fechado, alcança também o run sem linha. O dano deixou de ser permanente; virou uma Issue
 * estacionada em {@code BLOCKED} pela varredura. O cinto segue sendo a defesa certa: ele é a FONTE
 * (liquida na hora, com o motivo real), o reaper é a REDE (só sabe que ninguém mais roda). Sem
 * teste, ele era exatamente a peça que regride sem ninguém ver.
 *
 * <p>Os gates reais são fail-soft por dentro e não explodem sob demanda — por isso o cinto se prova
 * com um {@code ato} que explode, chamando o método de visibilidade de PACOTE (mesmo recurso do
 * {@code WorkspaceReaper.sweepTarget}: núcleo profundo testável).
 */
@SpringBootTest
@ActiveProfiles("test")
class GateDeSaidaSemDerrubarOSettleTest {

    @Autowired
    private AdapterRunExecutor executor;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    private RunContext runVivo() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null)).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.RUNNING)).getId();
        return new RunContext(companyId, agentId, runId, null);
    }

    @Test
    void gateQueEXPLODE_naoSobeParaOExecute_eViraStepVISIVEL() {
        RunContext contexto = runVivo();

        assertThatCode(() -> executor.semDerrubarOSettle(contexto, "gateFicticio", () -> {
            throw new IllegalStateException("regressão de amanhã");
        })).as("se isto sobe, o settle é pulado e a Issue trava para sempre")
                .doesNotThrowAnyException();

        // Anti-silêncio: o cinto segura, mas não engole — o incidente fica no StepTrace.
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(contexto.runId()))
                .anySatisfy(evento -> {
                    assertThat(evento.getType()).isEqualTo("GATE_DE_SAIDA_FALHOU");
                    assertThat(evento.getDetail()).contains("gateFicticio")
                            .contains("regressão de amanhã");
                });
    }

    /**
     * A RECUPERAÇÃO desprotegida (o 🔵 irmão): o {@code recordStep} do catch desce em
     * {@code findById().orElseThrow()}. Com o run inexistente — apagado pelo reaper de linhas,
     * company removida, banco fora — a gravação do incidente levantava DE DENTRO do catch e
     * escapava, <b>pulando o settle: o dano exato que o cinto existe para evitar</b>. Um cinto
     * cuja falha reproduz o dano que ele previne não é cinto.
     *
     * <p>O run inexistente aqui não é artifício: é a forma mais direta de fazer o registro falhar
     * sem mockar (a casa não usa mock de bean), e é um estado alcançável de verdade.
     */
    @Test
    void gateExplodeEOregistroDoIncidenteTambemFALHA_aindaAssimNaoDerrubaORun() {
        // Run que não existe no banco: o recordStep vai levantar lá dentro.
        RunContext runFantasma = new RunContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null);

        assertThatCode(() -> executor.semDerrubarOSettle(runFantasma, "gateFicticio", () -> {
            throw new IllegalStateException("regressão de amanhã");
        })).as("perder a linha do StepTrace é ruim; pular o settle e jogar a Issue na rede é pior")
                .doesNotThrowAnyException();
    }
}
