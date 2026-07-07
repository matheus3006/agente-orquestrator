package com.orquestrador.skills;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.execution.runtime.WorkspaceResolver;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecha o seam da Fatia 7a ponta-a-ponta: com a MESMA skill no set base (v1) e anexada à Issue (v2),
 * um Run real materializa a versão do ANEXO — provando que {@code resolveForRun} colapsa a união com
 * ANEXO-VENCE-A-BASE antes do {@link SkillMaterializer} no {@code AdapterRunExecutor}.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttachedSkillMaterializedOnRunTest {

    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private CompanySkillRepository companySkills;

    @TempDir
    Path skillsRoot;

    @Test
    void anexoSobrescreveBaseNoWorkspaceDoRun() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID board = UUID.randomUUID();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();

        // Instala a skill (v1) e a fixa no set base do agente pinando v1.
        Path dir = Files.createDirectories(skillsRoot.resolve("run-skill"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: run-skill\ndescription: d\n---\n# corpo BASE v1\n");
        indexer.index(companyId, skillsRoot);
        CompanySkill installed = companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0);
        skillSets.select(companyId, agentId, installed.getId());

        // A skill evolui para v2 na biblioteca; a base continua pinada em v1.
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: run-skill\ndescription: d\n---\n# corpo ANEXO v2\n");
        indexer.index(companyId, skillsRoot);

        // Anexa à Issue pela identidade GLOBAL (pina a corrente = v2) e roda um Run real.
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(), ActorType.BOARD, board, null);
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // O workspace do Run materializou a versão ANEXADA (v2), não a base (v1).
        Path skillMd = workspaces.resolve(companyId, agentId, issueId)
                .resolve(".claude/skills/run-skill/SKILL.md");
        assertThat(Files.exists(skillMd)).isTrue();
        assertThat(Files.readString(skillMd)).contains("ANEXO v2").doesNotContain("BASE v1");
    }
}
