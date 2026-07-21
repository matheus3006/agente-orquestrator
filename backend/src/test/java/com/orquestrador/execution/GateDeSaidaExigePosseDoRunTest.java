package com.orquestrador.execution;

import com.orquestrador.governance.approval.ApprovalRepository;
import com.orquestrador.governance.approval.ApprovalStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Os 4 gates de fim-de-run disparavam side-effect sem checar POSSE do run</b> (achado do PR #49,
 * aberto desde então; o {@code semDerrubarOSettle} do PR #53 endereçou EXCEÇÃO, não posse).
 *
 * <p><b>A "thread tardia", passo a passo.</b> O nó A roda o run R sobre a issue I. A trava de A
 * (pausa de GC, partição de rede) deixa o lease expirar; o reaper devolve R para {@code QUEUED}; o
 * nó B pega R, executa e liquida. O {@code settleAfterRun} de B casa o guard
 * {@code execution_lock_run_id = R} e <b>libera a trava</b>. Só então A acorda, o
 * {@code adapter.execute()} dele retorna — e A dispara os 4 gates <b>em nome de um run que já foi
 * liquidado por outro</b>, criando Approval/issue/parecer REAIS. O settle de A cai no vazio (o
 * guard protege a issue); os gates não tinham guard nenhum.
 *
 * <p><b>Por que a posse é a da ISSUE e não a do run.</b> É o MESMO predicado que
 * {@code tryBlock}/{@code tryComplete} usam no {@code where} — o guard que já arbitra esta corrida
 * no banco, reusado como pré-condição em vez de um invariante novo. <b>Limite conhecido, nomeado
 * para não virar alegação de completude:</b> se o nó B ainda estiver EXECUTANDO R (re-checkout com
 * o MESMO runId ⇒ a trava volta a valer R), a régua deixa A passar. Fecha o caso em que alguém já
 * liquidou — que é o caso que cria artefato duplicado — não a sobreposição de dois executores do
 * mesmo runId, que pede posse por DONO (nodeId) e é desenho de outro tamanho.
 */
@SpringBootTest
@ActiveProfiles("test")
class GateDeSaidaExigePosseDoRunTest {

    /** Um pedido de skill BEM-FORMADO: se o gate rodar, ele vira Approval de verdade. */
    private static final String REPLY_COM_PEDIDO = """
            trabalhei na issue e preciso de uma ferramenta.

            ```pedido-skill
            {"skillKey": "playwright"}
            ```
            """;

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
    @Autowired
    private IssueService issues;
    @Autowired
    private ApprovalRepository approvals;

    private record Cena(UUID companyId, Agent agent, UUID issueId, RunContext run) {
    }

    /** Uma issue em curso, travada pelo run que a pegou — o estado normal na hora dos gates. */
    private Cena issueEmCursoSobORun() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = agents.save(new Agent(UUID.randomUUID(), companyId, "A", "worker", null));
        Issue issue = issues.create(companyId, "entrega em curso", null);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agent.getId(),
                issue.getId(), RunStatus.RUNNING)).getId();
        issues.checkout(companyId, issue.getId(), agent.getId(), runId);
        return new Cena(companyId, agent, issue.getId(),
                new RunContext(companyId, agent.getId(), runId, issue.getId()));
    }

    private long approvalsDa(UUID companyId) {
        return approvals.findByCompanyIdAndStatusOrderByRequestedAt(companyId, ApprovalStatus.PENDING).size();
    }

    @Test
    void runQuePERDEUAPosse_temOSideEffectBARRADO_eODescarteFicaVISIVEL() {
        Cena cena = issueEmCursoSobORun();
        // A "thread tardia": outro nó já liquidou este run — o settle guardado liberou a trava e o
        // run terminou FAILED. O adapter do nó preso só agora retorna.
        issues.settleAfterRun(cena.companyId(), cena.issueId(), cena.run().runId(),
                RunOutcome.failure("nó preso: lease expirado e run liquidado por outro"));

        executor.entregarSaidaAosGates(cena.run(), cena.agent(), REPLY_COM_PEDIDO, false);

        assertThat(approvalsDa(cena.companyId()))
                .as("um run que já não detém a issue não cria Approval em nome dela")
                .isZero();
        // Anti-silêncio (ADR-0037 D1): barrar sem dizer seria trocar um dano por outro.
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(cena.run().runId()))
                .anySatisfy(evento -> {
                    assertThat(evento.getType()).isEqualTo("GATE_DE_SAIDA_SEM_POSSE");
                    assertThat(evento.getDetail()).contains(cena.issueId().toString());
                });
    }

    /**
     * O par negativo, sem o qual "barra sempre" passaria por conserto: com a posse INTACTA — o
     * caminho de 100% dos runs saudáveis — o side-effect acontece como sempre aconteceu.
     */
    @Test
    void runQueAINDADetemAIssue_segueDisparandoOSideEffect() {
        Cena cena = issueEmCursoSobORun();

        executor.entregarSaidaAosGates(cena.run(), cena.agent(), REPLY_COM_PEDIDO, false);

        assertThat(approvalsDa(cena.companyId()))
                .as("o dono da issue continua sendo ouvido — a cerca não pode calar o caminho normal")
                .isEqualTo(1);
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(cena.run().runId()))
                .noneSatisfy(evento -> assertThat(evento.getType()).isEqualTo("GATE_DE_SAIDA_SEM_POSSE"));
    }
}
