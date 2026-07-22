package com.orquestrador.skills;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
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
 * União com precedência (Fatia 7a): {@code resolveForRun} = base ∪ anexos da Issue, colapsado por skill
 * com ANEXO-VENCE-A-BASE; anexo imutável e idempotente; efeito efêmero (some quando a Issue fecha).
 */
@SpringBootTest
@ActiveProfiles("test")
class IssueSkillAttachmentServiceTest {

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
    @Autowired
    private IssueSkillAttachmentRepository attachments;
    @Autowired
    private HeartbeatRunRepository runs;

    @TempDir
    Path skillsRoot;

    private CompanySkill installSkill(UUID companyId, String skillKey, String body) throws IOException {
        Path dir = Files.createDirectories(skillsRoot.resolve(skillKey));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skillKey + "\ndescription: d\n---\n" + body);
        indexer.index(companyId, skillsRoot);
        return companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0);
    }

    @Test
    void anexoVenceBaseNaUniaoEExpiraQuandoIssueFecha() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID board = UUID.randomUUID();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();

        // Instala a skill (v1) e a fixa no SkillSet base do agente pinando v1.
        CompanySkill installed = installSkill(companyId, "uniao-skill", "# corpo BASE v1\n");
        skillSets.select(companyId, agentId, installed.getId());

        // A skill evolui para v2 na biblioteca (a base continua pinada em v1 — select não re-pina sozinho).
        Path dir = skillsRoot.resolve("uniao-skill");
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: uniao-skill\ndescription: d\n---\n# corpo ANEXO v2\n");
        indexer.index(companyId, skillsRoot);

        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        // Sem anexo: resolveForRun = base (v1).
        assertThat(skillSets.resolveForRun(companyId, agentId, issueId)).singleElement()
                .satisfies(version -> assertThat(version.getBody()).contains("BASE v1"));

        // Anexa (pina a versão corrente = v2). ANEXO-VENCE: a união materializa v2, não v1.
        skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(), ActorType.BOARD, board, null);
        List<CompanySkillVersion> effective = skillSets.resolveForRun(companyId, agentId, issueId);
        assertThat(effective).singleElement()
                .satisfies(version -> assertThat(version.getBody()).contains("ANEXO v2"));

        // Efeito efêmero: fechada a Issue (checkout + complete → DONE), a união volta à base (v1);
        // o registro do anexo PERSISTE (imutável).
        // Run REAL do motor — o checkout exige procedencia do runId (Fatia I-ter).
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.RUNNING)).getId();
        assertThat(issues.checkout(companyId, issueId, agentId, runId).acquired()).isTrue();
        assertThat(issues.complete(companyId, issueId, runId, agentId)).isTrue();

        assertThat(skillSets.resolveForRun(companyId, agentId, issueId)).singleElement()
                .satisfies(version -> assertThat(version.getBody()).contains("BASE v1"));
        assertThat(attachments.findByCompanyIdAndIssueId(companyId, issueId)).hasSize(1);
    }

    @Test
    void reAnexoDaMesmaVersaoEhIdempotente() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID board = UUID.randomUUID();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        CompanySkill installed = installSkill(companyId, "idem-skill", "# corpo\n");
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        IssueSkillAttachment first = skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(),
                ActorType.BOARD, board, null);
        IssueSkillAttachment second = skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(),
                ActorType.BOARD, board, null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(attachments.findByCompanyIdAndIssueId(companyId, issueId)).hasSize(1);
    }

    @Test
    void entreAnexosDaMesmaSkillOMaisRecenteVence() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID board = UUID.randomUUID();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        CompanySkill installed = installSkill(companyId, "multi-skill", "# corpo v1\n");
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        Path dir = skillsRoot.resolve("multi-skill");

        // Anexa a v2 (append-only), depois a v3 da MESMA skill à mesma Issue.
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: multi-skill\ndescription: d\n---\n# corpo v2\n");
        indexer.index(companyId, skillsRoot);
        skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(), ActorType.BOARD, board, null);

        Files.writeString(dir.resolve("SKILL.md"), "---\nname: multi-skill\ndescription: d\n---\n# corpo v3\n");
        indexer.index(companyId, skillsRoot);
        skillSets.attachToIssue(companyId, issueId, agentId, installed.getSkillId(), ActorType.BOARD, board, null);

        // Dois registros persistem (imutável/append-only); a união colapsa por skill e o ANEXO MAIS RECENTE
        // (v3, maior created_at) vence — trava a ordem contra uma regressão futura (ex.: 7c pegar o primeiro).
        assertThat(attachments.findByCompanyIdAndIssueId(companyId, issueId)).hasSize(2);
        assertThat(skillSets.resolveForRun(companyId, agentId, issueId)).singleElement()
                .satisfies(version -> assertThat(version.getBody()).contains("v3").doesNotContain("v2"));
    }
}
