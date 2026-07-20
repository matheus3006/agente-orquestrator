package com.orquestrador.work.issue;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceReaper;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        UUID runId = UUID.randomUUID();
        issues.checkout(companyId, issueId, agentId, runId);
        issues.complete(companyId, issueId, runId);
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
