package com.orquestrador.work.issue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.execution.runtime.DatabaseClock;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceReaper;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A ADR-0049 tornou {@code DONE} reabrível e <b>não varreu quem vivia do invariante "DONE é
 * terminal"</b> (conferência dos PRs #51/#52, 🟠 3). O {@code WorkspaceReaper} define terminal
 * como {@code {DONE, CANCELLED}} com retenção default de <b>7 dias</b> sobre {@code updated_at} e
 * — ao contrário do {@code RowRetentionReaper} — <b>sem exceção de pin de review</b>. Reabrir uma
 * entrega DONE mais velha que isso devolvia a issue ao jogo com o artefato JÁ DELETADO: o mesmo
 * assignee re-executava contra um workspace vazio, <b>em silêncio</b>.
 *
 * <p><b>Decisão do dono (2026-07-20, D-FP-1): FALHAR ALTO.</b> O endpoint de reabrir checa se o
 * workspace ainda existe e RECUSA com mensagem clara. Racional: transforma perda silenciosa em
 * recusa explícita — o dono decide sabendo do custo, em vez de descobrir depois que o agente
 * refez do zero. Descartadas: fixar o workspace (cresce disco indefinidamente) e avisar-e-seguir.
 *
 * <p><b>Por que o teste dirige o REAPER DE VERDADE</b> em vez de gravar o evento à mão: o risco
 * real desta cerca não é a comparação, é os dois lados discordarem sobre QUAL evento conta
 * (ação, {@code entityType}, {@code entityId}). Mandando o reaper coletar de fato, o evento que a
 * porta lê é exatamente o que o coletor escreve. {@code retention-days=0} faz o DONE vencer na
 * hora — é o mesmo recurso que o {@code WorkspaceReaperTest} usa.
 */
@SpringBootTest(properties = "orquestrador.workspace.gc.retention-days=0")
@ActiveProfiles("test")
class ReaberturaComWorkspaceColetadoTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private WorkspaceReaper reaper;
    @Autowired
    private ExecutionTargets targets;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private DatabaseClock clock;
    @Autowired
    private HeartbeatRunRepository runs;

    private UUID companyId;

    @AfterEach
    void limpa() {
        if (companyId == null) {
            return;
        }
        Path root = workspaces.root().resolve(companyId.toString());
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Uma entrega fechada em DONE, com workspace materializado no alvo LOCAL. */
    private Entrega entregaConcluidaComWorkspace() {
        companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "entrega com artefato", null).getId();
        Path workspace = workspaces.resolve(companyId, agentId, issueId);
        // Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter).
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.SUCCEEDED)).getId();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.complete(companyId, issueId, runId, agentId);
        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        return new Entrega(issueId, workspace);
    }

    private record Entrega(UUID issueId, Path workspace) {
    }

    @Test
    void workspaceJaColetadoPeloGC_reaberturaERECUSADA_eAIssueFicaComoEstava() {
        Entrega entrega = entregaConcluidaComWorkspace();
        UUID issueId = entrega.issueId();

        // O ciclo agendado de verdade (o que o HeartbeatScheduler chama), não um atalho de teste.
        reaper.sweepOnce();
        assertThat(Files.exists(entrega.workspace()))
                .as("pré-condição: o GC coletou mesmo o workspace desta entrega")
                .isFalse();

        assertThatThrownBy(() -> issues.reabrirEntregaAprovada(companyId, issueId,
                "o teste do caminho triste não existe", ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("coletado pelo GC");

        // FALHAR ALTO é recusa, não meia-reabertura: a issue fica exatamente como estava.
        assertThat(issues.get(companyId, issueId).getStatus())
                .as("a recusa reverte o UPDATE guardado — nada de DONE→BLOCKED pela metade")
                .isEqualTo(IssueStatus.DONE);
    }

    /**
     * <b>O 2º falso-negativo — o de DENTRO de casa</b> (achado da conferência do PR #53 por dois
     * verificadores; o follow-up órfão entre os PRs #54/#55). O
     * {@code WorkspaceReaper.recordCollected} engole a exceção da auditoria de propósito — a
     * remoção já aconteceu e derrubar o sweep dos demais seria pior. A escolha nasceu quando o
     * evento era só rastro; <b>desde a D-FP-1 ele é a FONTE desta cerca</b>. Neste caminho o
     * workspace some e a cerca fica CEGA: a reabertura passa contra workspace vazio, que é
     * exatamente a perda silenciosa que a D-FP-1 veio matar.
     *
     * <p>Este teste PINA as duas metades da verdade: a cegueira é real (não é hipótese de
     * corredor) e, por isso, o operador TEM de ser avisado com o nome da consequência. O remédio
     * não é fazer a cerca enxergar — a auditoria falhou justamente porque a gravação não foi —
     * é não deixar o silêncio de pé.
     */
    @Test
    void auditoriaQueFALHA_deixaACercaCEGA_masOAvisoNOMEIAAConsequencia() {
        Entrega entrega = entregaConcluidaComWorkspace();

        ListAppender<ILoggingEvent> erros = new ListAppender<>();
        erros.start();
        ch.qos.logback.classic.Logger reaperLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkspaceReaper.class);
        reaperLogger.addAppender(erros);
        try {
            reaperComAuditoriaQuebrada().sweepOnce();
        } finally {
            reaperLogger.detachAppender(erros);
        }

        assertThat(Files.exists(entrega.workspace()))
                .as("a remoção acontece de qualquer jeito — falha de auditoria NÃO a desfaz")
                .isFalse();
        // A cegueira, medida e não suposta: sem o evento durável, a cerca deixa passar.
        assertThatCode(() -> issues.reabrirEntregaAprovada(companyId, entrega.issueId(),
                "reabrindo com o artefato já deletado", ActorType.BOARD, UUID.randomUUID()))
                .as("sem o evento a régua devolve vazio e a cerca não tem o que recusar")
                .doesNotThrowAnyException();

        assertThat(erros.list)
                .filteredOn(evento -> evento.getLevel() == Level.ERROR)
                .as("quem lê o log tem de saber que engoliu a FONTE de uma cerca, não só um rastro")
                .anySatisfy(evento -> assertThat(evento.getFormattedMessage())
                        .contains("LOAD-BEARING")
                        .contains("reabertura")
                        .contains(entrega.issueId().toString()));
    }

    /**
     * O reaper de verdade, com a ÚNICA peça trocada sendo a que precisa falhar. Construído à mão
     * (e não com spy no bean) para que o caminho exercitado seja o mesmo {@code sweepOnce} agendado.
     */
    private WorkspaceReaper reaperComAuditoriaQuebrada() {
        ActivityLogService auditoriaQuebrada = mock(ActivityLogService.class);
        when(auditoriaQuebrada.record(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("activity_log fora do ar"));
        return new WorkspaceReaper(issueRepository, targets, workspaces, auditoriaQuebrada, clock,
                true, 0L);
    }

    /**
     * O contraponto que impede a cerca de virar "reabrir nunca funciona": com o workspace no
     * lugar, a reabertura segue passando. Sem este par, um bug que recusasse SEMPRE passaria
     * despercebido pelo teste de cima.
     */
    @Test
    void workspaceAindaNoLugar_reaberturaSEGUEFUNCIONANDO() {
        UUID issueId = entregaConcluidaComWorkspace().issueId();

        assertThatCode(() -> issues.reabrirEntregaAprovada(companyId, issueId,
                "quero uma segunda passada", ActorType.BOARD, UUID.randomUUID()))
                .doesNotThrowAnyException();

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }
}
