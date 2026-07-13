package com.orquestrador.skills;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.Approval;
import com.orquestrador.governance.approval.ApprovalActionType;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.governance.approval.ApprovalStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-AT1 (grill agente-como-tool): fecha o seam ponta-a-ponta no caminho REAL — o EXECUTOR de uma
 * Issue emite {@code ```pedido-skill} na saída do Run (o mesmo canal de saída/StepTrace que o
 * {@code ProposalScanner} já usa pro CEO); o Run de Issue não tem saída "em forma de turno" (sem
 * {@code outputSchema}, D6 nota-de-lealdade), então o scanner opera sobre o texto cru reconstruído
 * pelo {@code RunFinalOutput} — a MESMA regra do report-por-Issue ({@code IssueLedger}).
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillRequestOnRunTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private CompanySkillRepository companySkills;

    @TempDir
    Path skillsRoot;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID contrataExecutor(UUID companyId, String comandoShell) {
        Agent executor = new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null);
        executor.configureRuntime("process", comandoShell, "LOCAL");
        return agents.save(executor).getId();
    }

    /** Um comando curto mesmo com N skills (coluna runtime_command é VARCHAR(255) no H2 de teste). */
    private static String pedidoSkillShell(String... skillKeys) {
        return "for k in " + String.join(" ", skillKeys)
                + "; do printf '```pedido-skill\\n{\"skillKey\": \"%s\"}\\n```\\n' \"$k\"; done";
    }

    @Test
    void executorPedeSkillForaDoSetViraApprovalPending() {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId, pedidoSkillShell("tdd"));
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<Approval> pending = approvals.queue(companyId, ApprovalStatus.PENDING);
        assertThat(pending).hasSize(1);
        Approval pedido = pending.get(0);
        assertThat(pedido.getActionType()).isEqualTo(ApprovalActionType.SKILL_INSTALL_REQUEST);
        assertThat(pedido.getRequestedByAgentId()).isEqualTo(executorId);
        assertThat(pedido.getTargetDetail()).contains("tdd").contains(issueId.toString());
    }

    /** D4 (teto): a MESMA skill pedida 2× no mesmo run vira 1 item só — dedup dentro do run. */
    @Test
    void mesmaSkillPedidaDuasVezesNoMesmoRunViraUmSoItem() {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId, pedidoSkillShell("dup-skill", "dup-skill"));
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(approvals.queue(companyId, ApprovalStatus.PENDING)).hasSize(1);
    }

    /** D4 (teto): o 6º pedido DISTINTO no mesmo run é descartado com evento VISÍVEL no StepTrace. */
    @Test
    void sextoPedidoDistintoNoMesmoRunEDescartadoComEventoVisivel() {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId,
                pedidoSkillShell("sk1", "sk2", "sk3", "sk4", "sk5", "sk6"));
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(approvals.queue(companyId, ApprovalStatus.PENDING)).hasSize(5);
        var run = runs.findFirstByCompanyIdAndAgentIdOrderByCreatedAtDesc(companyId, executorId).orElseThrow();
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo("SKILL_REQUEST_DISCARDED");
                    assertThat(event.getDetail()).contains("sk6").contains("além do teto");
                });
    }

    /** Dedup contra "instalado" (D4): a skill já efetiva pro executor nesta Issue não gera pedido novo. */
    @Test
    void skillJaNoSetEfetivoDoExecutorNaoGeraNovoPedido() throws IOException {
        UUID companyId = novaCompany();
        UUID executorId = contrataExecutor(companyId, pedidoSkillShell("ja-tenho"));

        Path dir = Files.createDirectories(skillsRoot.resolve("ja-tenho"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: ja-tenho\ndescription: d\n---\n# corpo\n");
        indexer.index(companyId, skillsRoot);
        CompanySkill installed = companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0);
        skillSets.select(companyId, executorId, installed.getId()); // já está no set BASE do executor

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        wakeups.enqueue(companyId, executorId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(approvals.queue(companyId, ApprovalStatus.PENDING))
                .as("já tem a skill efetivamente — pedir de novo não abre item na fila").isEmpty();
    }

    // A materialização em disco do PRÓXIMO run (pedido→PENDING→APPROVE→resolveForRun já inclui a
    // skill) é reuso PURO de dois mecanismos já provados: ApprovalGateTest
    // (skillInstallRequestAprovadoAnexaASkillNaIssueParaOAgenteQuePediu, o dispatch chama
    // SkillSetService.attachToIssue) e AttachedSkillMaterializedOnRunTest (resolveForRun → arquivo
    // no workspace do Run). Encadear um 2º dispatchOnce() aqui fecharia a Issue de teste no
    // 1º run (process "sucesso" ⟹ settleAfterRun ⟹ DONE) e o anexo expiraria ANTES do 2º run —
    // um artefato do fixture "process", não do produto; a prova viva com CLIs reais fica no /verify.
}
