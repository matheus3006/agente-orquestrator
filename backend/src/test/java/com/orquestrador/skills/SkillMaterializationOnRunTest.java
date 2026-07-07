package com.orquestrador.skills;

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
 * Fecha o seam (Fatia 6) ponta-a-ponta: um agente com uma skill selecionada, ao rodar um Run real,
 * tem o SkillSet materializado em workDir/.claude/skills/<key>/SKILL.md antes do adapter executar.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillMaterializationOnRunTest {

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
    void skillSelecionadaEhMaterializadaNoWorkspaceDoRun() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();

        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();

        // instala uma skill na company e seleciona para o agente
        Path dir = Files.createDirectories(skillsRoot.resolve("mat-tdd"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: mat-tdd\ndescription: d\n---\n# TDD\ncorpo\n");
        indexer.index(companyId, skillsRoot);
        UUID companySkillId = companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0).getId();
        skillSets.select(companyId, agentId, companySkillId);

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // o SkillSet foi materializado no workspace do Run
        Path skillMd = workspaces.resolve(companyId, agentId, issueId)
                .resolve(".claude/skills/mat-tdd/SKILL.md");
        assertThat(Files.exists(skillMd)).isTrue();
        assertThat(Files.readString(skillMd)).contains("# TDD").contains("mat-tdd");
    }
}
