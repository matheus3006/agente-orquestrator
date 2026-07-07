package com.orquestrador.skills;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Install-on-attach (Fatia 7c, fold da 7a): anexar pela identidade GLOBAL resolve o install-state —
 * AVAILABLE instala (source=CATALOG) + anexa (2 eventos), INSTALLED só anexa (1 evento); skill global
 * inexistente → 404; corrida de install concorrente → exatamente 1 install.
 */
@SpringBootTest
@ActiveProfiles("test")
class InstallOnAttachServiceTest {

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
    private SkillRepository skills;
    @Autowired
    private CompanySkillRepository companySkills;
    @Autowired
    private ActivityLogRepository activityLog;

    @TempDir
    Path skillsRoot;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    private UUID newAgent(UUID companyId) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "echo ok", "LOCAL");
        return agents.save(agent).getId();
    }

    private void writeSkillFile(String skillKey, String body) throws IOException {
        Path dir = Files.createDirectories(skillsRoot.resolve(skillKey));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skillKey + "\ndescription: d\n---\n" + body);
    }

    private long countAction(UUID companyId, ActivityAction action) {
        return activityLog.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(entry -> entry.getAction().equals(action.name())).count();
    }

    @Test
    void availableInstalaDoCatalogoEAnexaComDoisEventos() throws IOException {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID board = UUID.randomUUID();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        // A skill existe SÓ no catálogo global (não instalada na company) → AVAILABLE.
        writeSkillFile("avail-skill", "# corpo global\n");
        indexer.syncGlobalCatalog(skillsRoot);
        UUID skillId = skills.findBySkillKey("avail-skill").orElseThrow().getId();
        assertThat(companySkills.findByCompanyIdAndSkillId(companyId, skillId)).isEmpty();

        skillSets.attachToIssue(companyId, issueId, agentId, skillId, ActorType.BOARD, board, null);

        // Instalou copiando do catálogo (source=CATALOG) e anexou; 2 eventos (INSTALLED + ATTACHED).
        assertThat(companySkills.findByCompanyIdAndSkillId(companyId, skillId))
                .get().extracting(CompanySkill::getSource).isEqualTo("CATALOG");
        assertThat(countAction(companyId, ActivityAction.SKILL_INSTALLED)).isEqualTo(1);
        assertThat(countAction(companyId, ActivityAction.SKILL_ATTACHED)).isEqualTo(1);
    }

    @Test
    void installedApenasAnexaComUmEvento() throws IOException {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID board = UUID.randomUUID();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        // Já instalada na company (index instala) → INSTALLED.
        writeSkillFile("inst-skill", "# corpo\n");
        indexer.index(companyId, skillsRoot);
        UUID skillId = skills.findBySkillKey("inst-skill").orElseThrow().getId();
        int companySkillsBefore = companySkills.findByCompanyIdOrderByCreatedAt(companyId).size();

        skillSets.attachToIssue(companyId, issueId, agentId, skillId, ActorType.BOARD, board, null);

        // Não reinstalou; 0 INSTALLED, 1 ATTACHED; nenhuma company_skill nova.
        assertThat(countAction(companyId, ActivityAction.SKILL_INSTALLED)).isEqualTo(0);
        assertThat(countAction(companyId, ActivityAction.SKILL_ATTACHED)).isEqualTo(1);
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(companyId)).hasSize(companySkillsBefore);
    }

    @Test
    void skillGlobalInexistenteNaoAnexa() {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        assertThatThrownBy(() -> skillSets.attachToIssue(companyId, issueId, agentId, UUID.randomUUID(),
                ActorType.BOARD, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void instalacoesConcorrentesDaMesmaSkillGeramUmSoInstall() throws Exception {
        UUID companyId = newCompany();
        UUID agentId = newAgent(companyId);
        UUID board = UUID.randomUUID();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        writeSkillFile("race-install", "# corpo\n");
        indexer.syncGlobalCatalog(skillsRoot);
        UUID skillId = skills.findBySkillKey("race-install").orElseThrow().getId();

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<Throwable>> outcomes = new ArrayList<>();
        for (int racerIndex = 0; racerIndex < racers; racerIndex++) {
            outcomes.add(pool.submit(() -> {
                startLine.await();
                try {
                    skillSets.attachToIssue(companyId, issueId, agentId, skillId, ActorType.BOARD, board, null);
                    return null;
                } catch (RuntimeException attachFailure) {
                    return attachFailure;
                }
            }));
        }
        startLine.countDown();

        int successes = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<Throwable> outcome : outcomes) {
            Throwable failure = outcome.get();
            if (failure == null) {
                successes++;
            } else {
                failures.add(failure);
            }
        }
        pool.shutdown();

        // Exatamente UM install (a uq_company_skill arbitra); perdedores tomam 409, nunca 500 nem install duplo.
        assertThat(companySkills.findByCompanyIdAndSkillId(companyId, skillId)).isPresent();
        assertThat(countAction(companyId, ActivityAction.SKILL_INSTALLED)).isEqualTo(1);
        assertThat(successes).isGreaterThanOrEqualTo(1);
        assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
    }
}
