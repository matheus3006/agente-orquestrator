package com.orquestrador.execution;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.runtime.DockerExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.LocalExecutionTarget;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.CompanyMemory;
import com.orquestrador.memory.CompanyMemoryRepository;
import com.orquestrador.memory.MemoryKind;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.skills.CompanySkillRepository;
import com.orquestrador.skills.SkillIndexer;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fatia AGENTS.md ponta-a-ponta (ADR-0032): um agente codex deixa de rodar "pelado" — a carta
 * materializa como {@code AGENTS.md} na raiz e as skills como {@code .codex/skills/<chave>/SKILL.md}
 * (os DOIS canais NATIVOS do codex, sonda S2), enquanto a faxina permanente (D6) apaga o espelho
 * {@code .claude/} que o codex nunca leu — troca de motor nunca deixa cérebro velho no chão.
 *
 * <p>O run do codex é STUBADO no alvo (o binário não roda em teste); o que se prova aqui é o
 * pipeline do executor: convenção ativa → materializers paramétricos → faxina → união no escritor.
 */
@SpringBootTest
@ActiveProfiles("test")
class CodexWorkspaceConventionOnRunTest {

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
    private ConversationService conversations;
    @Autowired
    private CompanyMemoryRepository memories;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private SkillSetService skillSets;
    @Autowired
    private CompanySkillRepository companySkills;
    @Autowired
    private SubtreeRecorder recorder;

    @TempDir
    Path skillsRoot;

    @AfterEach
    void limpaRecorder() {
        recorder.writes.clear();
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID ceoCodex(UUID companyId) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("codex", null, "LOCAL");
        return agents.save(ceo).getId();
    }

    private UUID instalaESeleciona(UUID companyId, UUID agentId, String key, String body) throws Exception {
        Path dir = Files.createDirectories(skillsRoot.resolve(key));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + key + "\ndescription: d\n---\n" + body);
        indexer.index(companyId, skillsRoot);
        UUID companySkillId = companySkills.findByCompanyIdOrderByCreatedAt(companyId).get(0).getId();
        skillSets.select(companyId, agentId, companySkillId);
        return companySkillId;
    }

    private void posta(UUID companyId, String mensagem) {
        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), mensagem, ActorType.BOARD, UUID.randomUUID());
    }

    @Test
    void turnoCodexMaterializaCartaESkillsNosCanaisNativosEFaxinaOEspelhoClaude() throws Exception {
        UUID companyId = novaCompany();
        UUID ceoId = ceoCodex(companyId);
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Carta do Atlas\n- marcador LARANJA-VERDE-77", 1));
        instalaESeleciona(companyId, ceoId, "sonda-g3", "# Sonda\npalavra BANANA-AZUL-42\n");

        // O workspace "pelado" real (S3): espelho .claude/ materializado pré-G3, que o codex nunca leu.
        Path workDir = workspaces.resolve(companyId, ceoId, null);
        Files.createDirectories(workDir.resolve(".claude/skills/velha"));
        Files.writeString(workDir.resolve(".claude/skills/velha/SKILL.md"), "cerebro velho");
        Files.writeString(workDir.resolve(".claude/CLAUDE.md"), "carta velha");

        posta(companyId, "oi");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // Canais nativos do codex: carta VERBATIM na raiz + skills na árvore .codex/skills.
        assertThat(Files.readString(workDir.resolve("AGENTS.md")))
                .isEqualTo("# Carta do Atlas\n- marcador LARANJA-VERDE-77");
        assertThat(Files.readString(workDir.resolve(".codex/skills/sonda-g3/SKILL.md")))
                .contains("BANANA-AZUL-42");
        // D8: a distillation entra pelo pipe único — existe nos 2 motores, zero caso especial.
        assertThat(Files.exists(workDir.resolve(".codex/skills/distillation/SKILL.md"))).isTrue();
        // D6: faxina permanente — o espelho claude (inativo) some do chão INTEIRO, inclusive o
        // diretório pai vazio (critério literal do §VENDO: ".claude/ NÃO existe").
        assertThat(Files.exists(workDir.resolve(".claude"))).isFalse();
    }

    @Test
    void turnoEscreveAUniaoDasConvencoesNoEscritorDeWorkspace() {
        // D6 no DOCKER de graça (P2): o escritor é REPLACE por subtree — listar a união faz o
        // volume remover o que não existe no host. Issue: skillsSubdirs; turno: + memoryFiles.
        UUID companyId = novaCompany();
        ceoCodex(companyId);
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta", 1));

        posta(companyId, "oi");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(recorder.writes).hasSize(1);
        assertThat(recorder.writes.get(0)).containsExactly(
                ".claude/skills", ".codex/skills", ".claude/CLAUDE.md", "AGENTS.md");
    }

    @Test
    void runDeIssueCodexMaterializaSoSkillsECartaDoAgenteFicaEmPaz() throws Exception {
        UUID companyId = novaCompany();
        Agent worker = new Agent(UUID.randomUUID(), companyId, "W", "worker", null);
        worker.configureRuntime("codex", "true", "LOCAL");
        UUID workerId = agents.save(worker).getId();
        instalaESeleciona(companyId, workerId, "mat-codex", "# Codex\ncorpo\n");

        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        Path workDir = workspaces.resolve(companyId, workerId, issueId);
        Files.createDirectories(workDir.resolve(".claude/skills/velha"));
        Files.writeString(workDir.resolve(".claude/skills/velha/SKILL.md"), "cerebro velho");
        // Um AGENTS.md que o AGENTE escreveu no próprio workspace de Issue (D5): o pipeline
        // nunca o toca em run de Issue — espelho exato do contrato do .claude/CLAUDE.md.
        Files.writeString(workDir.resolve("AGENTS.md"), "notas do proprio agente");

        wakeups.enqueue(companyId, workerId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(Files.exists(workDir.resolve(".codex/skills/mat-codex/SKILL.md"))).isTrue();
        assertThat(Files.exists(workDir.resolve(".claude/skills"))).isFalse();
        assertThat(Files.readString(workDir.resolve("AGENTS.md"))).isEqualTo("notas do proprio agente");
        assertThat(recorder.writes).hasSize(1);
        assertThat(recorder.writes.get(0)).containsExactly(".claude/skills", ".codex/skills");
    }

    @Test
    void turnoCodexSemCartaRemoveOAgentsMd() throws Exception {
        // Reescreve-ou-remove atravessa a convenção (D5): sem carta consolidada, o AGENTS.md
        // some — nunca servir carta velha de outra versão do workspace.
        UUID companyId = novaCompany();
        UUID ceoId = ceoCodex(companyId);
        Path workDir = workspaces.resolve(companyId, ceoId, null);
        Files.writeString(workDir.resolve("AGENTS.md"), "carta de um run antigo");

        posta(companyId, "oi");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(Files.exists(workDir.resolve("AGENTS.md"))).isFalse();
    }

    @Test
    void turnoClaudeFaxinaOEspelhoCodexDeixadoPorMotorAnterior() throws Exception {
        // O simétrico (D6): trocar codex→claude também não deixa cérebro velho — AGENTS.md e
        // .codex/skills de um motor anterior somem quando a convenção ativa é a claude.
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta", 1));

        Path workDir = workspaces.resolve(companyId, ceoId, null);
        Files.createDirectories(workDir.resolve(".codex/skills/velha"));
        Files.writeString(workDir.resolve(".codex/skills/velha/SKILL.md"), "cerebro velho");
        Files.writeString(workDir.resolve("AGENTS.md"), "carta do motor anterior");

        posta(companyId, "oi");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(Files.readString(workDir.resolve(".claude/CLAUDE.md"))).isEqualTo("# Carta");
        assertThat(Files.exists(workDir.resolve(".codex"))).isFalse();
        assertThat(Files.exists(workDir.resolve("AGENTS.md"))).isFalse();
    }

    @Test
    void adapterDesconhecidoFalhaAntesDoCheckoutDaIssueNaoDeixaLockPreso() {
        // D4 de carona: o forType sobe para ANTES da materialização/checkout — tipo desconhecido
        // falha o run com a Issue ainda LIVRE (lock nulo, status OPEN), não travada por um run morto.
        UUID companyId = novaCompany();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("motor-inexistente", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "tarefa", null).getId();

        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(issues.get(companyId, issueId).getExecutionLockRunId()).isNull();
    }

    static class SubtreeRecorder {
        final List<List<String>> writes = new CopyOnWriteArrayList<>();
    }

    @TestConfiguration
    static class AlvoComRunStubado {

        @Bean
        SubtreeRecorder subtreeRecorder() {
            return new SubtreeRecorder();
        }

        /**
         * LOCAL decorado: {@code run} STUBADO (exit 0 sem executar — o binário codex não roda em
         * teste e um process real não interessa aqui), escrita real (identidade no LOCAL) com a
         * lista de subtrees gravada, leitura real. Molde do {@code WorkspaceWriteOnRunTest}.
         */
        @Bean
        @Primary
        ExecutionTargets targetsComRunStubado(LocalExecutionTarget local, DockerExecutionTarget docker,
                                              SubtreeRecorder recorder) {
            ExecutionTarget stubado = new ExecutionTarget() {
                @Override
                public Type type() {
                    return Type.LOCAL;
                }

                @Override
                public Result run(Spec spec, Consumer<String> onStdoutLine) {
                    return new Result(0, null);
                }

                @Override
                public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
                    return local.readWorkspaceFile(spec, relativePath, maxBytes);
                }

                @Override
                public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
                    recorder.writes.add(List.copyOf(relativePaths));
                    return local.writeWorkspaceFiles(spec, relativePaths);
                }

                @Override
                public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
                    return local.startPreviewServer(spec);
                }

                @Override
                public PreviewServerStop stopPreviewServer(String serverName) {
                    return local.stopPreviewServer(serverName);
                }

                @Override
                public List<PreviewServerLive> listPreviewServers() {
                    return local.listPreviewServers();
                }

                @Override
                public String previewServerLogTail(String serverName, int maxLines) {
                    return local.previewServerLogTail(serverName, maxLines);
                }
            };
            return new ExecutionTargets(List.of(stubado, docker));
        }
    }
}
