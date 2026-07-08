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
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O escritor de workspace na fiacao do executor (par do leitor F.9/ADR-0021): a arvore que os
 * materializers escrevem no workDir do HOST so existe para o Run se atravessar o seam do
 * ExecutionTarget — no DOCKER o workspace e um volume nomeado invisivel ao filesystem do backend
 * (bug provado no smoke F.9d: skill anexada a agente claude-code+DOCKER nunca apareceu em
 * /workspace/.claude/skills). Estes testes pinam a fiacao pelo pipeline REAL: o executor publica
 * os subtrees materializados ANTES do adapter executar, com o Spec hermetico do escritor; e falha
 * do escritor e falha LEGIVEL do run — nunca um agente rodando sem as skills em silencio.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkspaceWriteOnRunTest {

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
    private WorkspaceWriteRecorder recorder;

    @AfterEach
    void limpaRecorder() {
        recorder.writes.clear();
        recorder.failWith = null;
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID agenteProcessLocal(UUID companyId, String command) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", command, "LOCAL");
        return agents.save(agent).getId();
    }

    @Test
    void runDeIssueEscreveOsSkillsNoWorkspaceComSpecHermetico() {
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, "true");
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(recorder.writes).hasSize(1);
        WorkspaceWrite write = recorder.writes.get(0);
        assertThat(write.relativePaths()).containsExactly(".claude/skills");
        assertThat(write.spec().workDir()).isEqualTo(workspaces.resolve(companyId, agentId, issueId));
        assertThat(write.spec().volumeName()).isEqualTo(workspaces.volumeName(companyId, agentId, issueId));
        // A imagem e a do PROPRIO adapter (process = plainShell), como no leitor do preview; e o
        // escritor e hermetico: NENHUM volume de credencial entra no Spec.
        assertThat(write.spec().image()).isEqualTo("alpine:3");
        assertThat(write.spec().credentialMounts()).isEmpty();
    }

    @Test
    void runDeTurnoEscreveSkillsECartaNoWorkspaceDoCeo() {
        // A carta so entra na lista em run de TURNO — espelha QUEM materializou (o
        // CompanyMemoryMaterializer nao roda em run de Issue; um CLAUDE.md que o agente
        // escreveu no proprio workspace de Issue nunca e tocado pelo escritor).
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        agents.save(ceo);
        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(recorder.writes).hasSize(1);
        assertThat(recorder.writes.get(0).relativePaths())
                .containsExactly(".claude/skills", ".claude/CLAUDE.md");
    }

    @Test
    void nenhumSubtreeOrqEMaterializado_oReplaceApagariaOBilheteDeHandoff() {
        // Guard-rail anti-regressão (M6, risco #3 — ADR-0030): .orq/* JAMAIS entra em
        // materializedSubtrees. writeWorkspaceFiles tem semântica REPLACE (membro ausente no host
        // some do volume) — materializar .orq/handoff apagaria o bilhete entre Runs sob Docker,
        // quebrando o loop de handoff. Este teste FALHA se alguém adicionar .orq/ à lista.
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        agents.save(ceo);
        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID());

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(recorder.writes).isNotEmpty();
        for (WorkspaceWrite write : recorder.writes) {
            assertThat(write.relativePaths()).noneMatch(path -> path.startsWith(".orq"));
        }
    }

    @Test
    void falhaDoEscritorEFalhaLegivelDoRunNuncaRodaSemAsSkills() {
        // O anti-F.9d: se a arvore nao chega ao workspace, o run NAO executa "sem skills em
        // silencio" — falha, e a Issue liquida BLOCKED pelo settle normal.
        UUID companyId = novaCompany();
        UUID agentId = agenteProcessLocal(companyId, "true");
        UUID issueId = issues.create(companyId, "tarefa", null).getId();
        wakeups.enqueue(companyId, agentId, issueId, "trabalhar");
        recorder.failWith = "daemon fora";

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.BLOCKED);
    }

    record WorkspaceWrite(ExecutionTarget.Spec spec, List<String> relativePaths) {
    }

    static class WorkspaceWriteRecorder {
        final List<WorkspaceWrite> writes = new CopyOnWriteArrayList<>();
        volatile String failWith;
    }

    @TestConfiguration
    static class EscritorObservavel {

        @Bean
        WorkspaceWriteRecorder workspaceWriteRecorder() {
            return new WorkspaceWriteRecorder();
        }

        /**
         * Registry com o LOCAL decorado: run/read reais, escrita observavel (e sabotavel). O
         * decorator e instancia anonima DENTRO do bean — nao vira bean de ExecutionTarget, entao
         * o registry default (que injeta List&lt;ExecutionTarget&gt;) nao ve tipo LOCAL duplicado.
         */
        @Bean
        @Primary
        ExecutionTargets targetsComEscritorObservavel(LocalExecutionTarget local, DockerExecutionTarget docker,
                                                      WorkspaceWriteRecorder recorder) {
            ExecutionTarget observado = new ExecutionTarget() {
                @Override
                public Type type() {
                    return Type.LOCAL;
                }

                @Override
                public Result run(Spec spec, Consumer<String> onStdoutLine) {
                    return local.run(spec, onStdoutLine);
                }

                @Override
                public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
                    return local.readWorkspaceFile(spec, relativePath, maxBytes);
                }

                @Override
                public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
                    recorder.writes.add(new WorkspaceWrite(spec, relativePaths));
                    if (recorder.failWith != null) {
                        return WorkspaceFilesWrite.failure(recorder.failWith);
                    }
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
            return new ExecutionTargets(List.of(observado, docker));
        }
    }
}
