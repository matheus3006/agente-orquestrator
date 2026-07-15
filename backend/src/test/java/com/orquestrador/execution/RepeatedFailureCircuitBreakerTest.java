package com.orquestrador.execution;

import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.execution.lineage.SpawnLineage;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueConversaTestSupport;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuit-breaker de erro repetido (ADR-0018 teto (c), R18-B): N falhas consecutivas do par
 * agente×issue param de re-enfileirar AQUELE par (o resto continua). Limiar default = 3. A mao
 * humana direta (root) fura o breaker — e o gesto que destrava. Prova pelo MOTOR real (H2): runs
 * FAILED de verdade, sem espiar o contador interno (o breaker e derivado do historico).
 */
@SpringBootTest
@ActiveProfiles("test")
class RepeatedFailureCircuitBreakerTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatRunStore store;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private ConversationService conversas;

    private static final List<RunStatus> ATIVOS =
            List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.RECOVERING);

    @Test
    void tresFalhasConsecutivasDoParBarramOProximoWakeupAutomatico() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);
        SpawnLineage automatica = automatica();

        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica); // 3a falha -> breaker abre

        WakeupOutcome quarto = wakeups.enqueue(company, agent, issue, "auto", automatica);

        assertThat(quarto.blocked()).isTrue();
        assertThat(quarto.runId()).isNull();
        assertThat(runs.findByAgentAndStatuses(agent, ATIVOS)).isEmpty();
    }

    @Test
    void oWakeupManualDireto_root_furaOBreaker() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);

        falhaUmRun(company, agent, issue, automatica());
        falhaUmRun(company, agent, issue, automatica());
        falhaUmRun(company, agent, issue, automatica()); // par travado

        // A mao humana direta (o overload de 4 args = SpawnLineage.root(), o do WakeupController):
        // fura o breaker. E o gesto que destrava.
        WakeupOutcome manual = wakeups.enqueue(company, agent, issue, "manual");

        assertThat(manual.blocked()).isFalse();
        assertThat(manual.created()).isTrue();
        assertThat(manual.runId()).isNotNull();
    }

    @Test
    void parDiferente_outroAgenteOuOutraIssue_naoEAfetado() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);

        falhaUmRun(company, agent, issue, automatica());
        falhaUmRun(company, agent, issue, automatica());
        falhaUmRun(company, agent, issue, automatica()); // (agent, issue) travado

        // OUTRO agente na MESMA issue: livre (a conta e do PAR)
        UUID outroAgente = novoAgente(company);
        WakeupOutcome outroNaMesmaIssue = wakeups.enqueue(company, outroAgente, issue, "auto", automatica());
        assertThat(outroNaMesmaIssue.blocked()).isFalse();
        assertThat(outroNaMesmaIssue.created()).isTrue();

        // MESMO agente em OUTRA issue: livre
        UUID outraIssue = novaIssue(company, agent);
        WakeupOutcome mesmoEmOutraIssue = wakeups.enqueue(company, agent, outraIssue, "auto", automatica());
        assertThat(mesmoEmOutraIssue.blocked()).isFalse();
        assertThat(mesmoEmOutraIssue.created()).isTrue();
    }

    @Test
    void falhaIntercaladaComSucessoNaoAbre() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);

        falhaUmRun(company, agent, issue, automatica());  // F
        sucedeUmRun(company, agent, issue, automatica()); // S -> zera a sequencia
        falhaUmRun(company, agent, issue, automatica());  // F
        falhaUmRun(company, agent, issue, automatica());  // F (so 2 seguidas apos o sucesso)

        WakeupOutcome proximo = wakeups.enqueue(company, agent, issue, "auto", automatica());
        assertThat(proximo.blocked()).isFalse();
        assertThat(proximo.created()).isTrue();
    }

    @Test
    void duasFalhasNaoAbrem() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);

        falhaUmRun(company, agent, issue, automatica());
        falhaUmRun(company, agent, issue, automatica()); // so 2 < 3

        WakeupOutcome terceiro = wakeups.enqueue(company, agent, issue, "auto", automatica());
        assertThat(terceiro.blocked()).isFalse();
        assertThat(terceiro.created()).isTrue();
    }

    @Test
    void oPromoteOnFinalize_oSegundoPontoDeEnfileiramento_tambemBarra() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);
        SpawnLineage automatica = automatica();

        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica); // par travado (3 FAILED)

        // O dono retoma pelo wakeup manual (root, fura) -> run em execucao.
        UUID runManual = wakeups.enqueue(company, agent, issue, "manual").runId();
        assertThat(store.claim(runManual, "node-x", Duration.ofMinutes(2))).isTrue();

        // Durante o run, um wakeup AUTOMATICO chega -> deferido (needs_followup), sem criar run novo.
        WakeupOutcome deferido = wakeups.enqueue(company, agent, issue, "auto", automatica);
        assertThat(deferido.runId()).isEqualTo(runManual);

        // O run manual FALHA (agora 4 FAILED): o promote-on-finalize NAO pode reenfileirar o par
        // travado — o 2o ponto de (re)enfileiramento tambem respeita o breaker.
        store.finishFailure(runManual, "node-x", "boom");

        assertThat(runs.findByAgentAndStatuses(agent, ATIVOS)).isEmpty();
    }

    @Test
    void oAvisoAoDono_umaVez_naConversaDaIssue_semRunNovo() {
        UUID company = novaCompany();
        UUID agent = novoAgente(company);
        UUID issue = novaIssue(company, agent);

        // Ancora a issue a uma conversa (em producao: a absorcao ADR-0037; aqui: suporte de teste).
        UUID conversa = conversas.open(company, "#N — trabalho").getId();
        IssueConversaTestSupport.ancorar(issueRepo, company, issue, conversa);

        SpawnLineage automatica = automatica();
        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica);
        falhaUmRun(company, agent, issue, automatica); // breaker abre

        // Tres wakeups automaticos barrados: o aviso ao dono e postado UMA vez (idempotencia por gatilho).
        wakeups.enqueue(company, agent, issue, "auto", automatica);
        wakeups.enqueue(company, agent, issue, "auto", automatica);
        wakeups.enqueue(company, agent, issue, "auto", automatica);

        List<ConversationTurn> avisos = conversas.thread(company, conversa).stream()
                .filter(turno -> RepeatedFailureNotice.isAviso(turno.getOwnerMessage()))
                .toList();
        assertThat(avisos).hasSize(1);
        ConversationTurn aviso = avisos.get(0);
        assertThat(aviso.getStatus()).isEqualTo(TurnStatus.ANSWERED); // ja respondido — nada a rodar
        assertThat(aviso.getRunId()).isNull();                        // sem Run novo (padrao M6)
        assertThat(aviso.getCeoReply()).contains(issue.toString());   // e sobre ESTE par agente×issue
        assertThat(runs.findByAgentAndStatuses(agent, ATIVOS)).isEmpty();
    }

    // --- helpers ---

    /** Um ciclo de vida completo de run que termina em FAILED (pelo chokepoint real). */
    private void falhaUmRun(UUID company, UUID agent, UUID issue, SpawnLineage lineage) {
        UUID runId = wakeups.enqueue(company, agent, issue, "auto", lineage).runId();
        assertThat(store.claim(runId, "node-x", Duration.ofMinutes(2))).isTrue();
        store.finishFailure(runId, "node-x", "boom");
    }

    /** Idem, terminando em SUCCEEDED — zera a sequencia de falhas do par. */
    private void sucedeUmRun(UUID company, UUID agent, UUID issue, SpawnLineage lineage) {
        UUID runId = wakeups.enqueue(company, agent, issue, "auto", lineage).runId();
        assertThat(store.claim(runId, "node-x", Duration.ofMinutes(2))).isTrue();
        store.finishSuccess(runId, "node-x");
    }

    /** Wakeup causado por agente (linhagem nao-raiz) — o que o breaker deve poder barrar. */
    private SpawnLineage automatica() {
        return SpawnLineage.root().descendTo(UUID.randomUUID());
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID novoAgente(UUID company) {
        return agents.save(new Agent(UUID.randomUUID(), company, "A", "worker", null)).getId();
    }

    private UUID novaIssue(UUID company, UUID agent) {
        return issues.createAtribuida(company, "T", null, 1, agent).getId();
    }
}
