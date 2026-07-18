package com.orquestrador.context;

import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.adapter.WorkspaceConvention;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.CompanyMemory;
import com.orquestrador.memory.CompanyMemoryMaterializer;
import com.orquestrador.memory.CompanyMemoryRepository;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionRepository;
import com.orquestrador.memory.MemoryAudience;
import com.orquestrador.memory.MemoryKind;
import com.orquestrador.planvalidation.PlanChecklistSection;
import com.orquestrador.planvalidation.PlanValidationProperties;
import com.orquestrador.skills.CompanySkillVersion;
import com.orquestrador.skills.HookMaterializer;
import com.orquestrador.skills.SkillMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunContextCompilerTest {

    private static final List<String> PUBLISHED_PATHS = List.of(
            ".claude/skills", ".codex/skills",
            ".claude/settings.json", ".claude/hooks/pretooluse-guard.js",
            ".codex/hooks.json", ".codex/hooks/pretooluse-guard.js",
            ".claude/CLAUDE.md", "AGENTS.md");

    @TempDir
    Path tempDir;

    @Test
    void pacotePreservaBytesOrdemEPathsParaClaudeCodexCeoExecutorLocalEDocker() throws Exception {
        for (WorkspaceConvention convention : WorkspaceConvention.KNOWN) {
            for (MemoryAudience audience : MemoryAudience.values()) {
                for (ExecutionTarget.Type targetType : ExecutionTarget.Type.values()) {
                    for (boolean memoryPresent : List.of(false, true)) {
                        Path workDir = tempDir.resolve(convention.memoryFile().replace('/', '-'))
                                .resolve(audience.name()).resolve(targetType.name()).resolve(String.valueOf(memoryPresent));
                        Compilation compilation = compile(workDir, convention, audience, targetType, memoryPresent);
                        ContextPackage contextPackage = compilation.contextPackage();

                        assertThat(contextPackage.publishedPaths()).containsExactlyElementsOf(PUBLISHED_PATHS);
                        assertThat(contextPackage.publishedPaths()).noneMatch(path -> path.startsWith(".orq"));
                        assertThat(compilation.writtenPaths()).containsExactlyElementsOf(PUBLISHED_PATHS);
                        assertThat(contextPackage.audience()).isEqualTo(audience);
                        assertThat(contextPackage.target()).isEqualTo(targetType);
                        assertThat(contextPackage.prompt()).isEqualTo("prompt vivo");
                        assertThat(contextPackage.outputSchema()).isEqualTo("{\"type\":\"object\"}");

                        Path skill = workDir.resolve(convention.skillsSubdir()).resolve("safe-skill/SKILL.md");
                        assertThat(Files.readAllBytes(skill)).isEqualTo("skill-body\n".getBytes(StandardCharsets.UTF_8));
                        Path memory = workDir.resolve(convention.memoryFile());
                        if (!memoryPresent) {
                            assertThat(memory).doesNotExist();
                        } else if (audience == MemoryAudience.CEO) {
                            assertThat(Files.readAllBytes(memory))
                                    .isEqualTo("carta\n\nplaybook".getBytes(StandardCharsets.UTF_8));
                        } else {
                            assertThat(Files.readAllBytes(memory))
                                    .isEqualTo(executorMemoryWithFeedback().getBytes(StandardCharsets.UTF_8));
                        }

                        assertThat(contextPackage.manifest().finalHash()).matches("[0-9a-f]{64}");
                        assertThat(contextPackage.manifest().sources()).isNotEmpty()
                                .allMatch(source -> source.classification() == ContextPolicy.Classification.CANONICAL)
                                .noneMatch(source -> source.address().contains("safe-skill")
                                        || source.address().contains("skill-body")
                                        || source.address().contains("prompt vivo"));
                    }
                }
            }
        }
    }

    @Test
    void hashFinalIndependeDoTargetECompilacaoNaoPublicaOrq() {
        ContextPackage local = compile(tempDir.resolve("local"), WorkspaceConvention.claude(),
                MemoryAudience.CEO, ExecutionTarget.Type.LOCAL, true).contextPackage();
        ContextPackage docker = compile(tempDir.resolve("docker"), WorkspaceConvention.claude(),
                MemoryAudience.CEO, ExecutionTarget.Type.DOCKER, true).contextPackage();

        assertThat(local.manifest().finalHash()).isEqualTo(docker.manifest().finalHash());
        assertThat(local.artifacts()).extracting(MaterializedArtifact::relativePath)
                .containsExactlyElementsOf(docker.artifacts().stream().map(MaterializedArtifact::relativePath).toList());
    }

    @Test
    void policyRecusaOrqMesmoQuandoAninhadoNaListaDeReplace() {
        ContextPolicy policy = new ContextPolicy();

        assertThatThrownBy(() -> policy.validatePublishedPaths(List.of(".claude/skills", ".orq/handoff")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".orq");
    }

    private Compilation compile(Path workDir, WorkspaceConvention convention, MemoryAudience audience,
                                ExecutionTarget.Type targetType, boolean memoryPresent) {
        UUID companyId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID agentId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID issueId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID runId = UUID.randomUUID();

        CompanyMemoryRepository memories = mock(CompanyMemoryRepository.class);
        DecisionRepository decisions = mock(DecisionRepository.class);
        if (memoryPresent && audience == MemoryAudience.EXECUTOR) {
            Decision feedback = mock(Decision.class);
            when(feedback.getSeq()).thenReturn(7);
            when(feedback.getDecidedAt()).thenReturn(Instant.parse("2026-07-16T12:00:00Z"));
            when(feedback.getArea()).thenReturn("produto");
            when(feedback.getOQueMudou()).thenReturn("corrigir fluxo");
            when(feedback.getRacional()).thenReturn("faltou a prova");
            when(decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(companyId, issueId))
                    .thenReturn(List.of(feedback));
        } else {
            when(decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(companyId, issueId)).thenReturn(List.of());
        }
        if (memoryPresent) {
            when(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA))
                    .thenReturn(Optional.of(new CompanyMemory(UUID.randomUUID(), companyId,
                            MemoryKind.CARTA, "carta", 1)));
            when(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK))
                    .thenReturn(Optional.of(new CompanyMemory(UUID.randomUUID(), companyId,
                            MemoryKind.PLAYBOOK, "playbook", 2)));
        } else {
            when(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA))
                    .thenReturn(Optional.empty());
            when(memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK))
                    .thenReturn(Optional.empty());
        }

        CompanySkillVersion skill = new CompanySkillVersion(UUID.randomUUID(), companyId, UUID.randomUUID(),
                7, "source-hash", "safe-skill", "desc", "trigger", "skill-body\n", "initial", null);
        List<ContextManifest> stored = new ArrayList<>();
        ContextManifestStore manifestStore = stored::add;
        List<String> written = new ArrayList<>();
        ExecutionTarget target = mock(ExecutionTarget.class);
        when(target.type()).thenReturn(targetType);
        when(target.writeWorkspaceFiles(any(), any())).thenAnswer(invocation -> {
            written.addAll(invocation.getArgument(1));
            return ExecutionTarget.WorkspaceFilesWrite.OK;
        });

        RunContextCompiler compiler = new DefaultRunContextCompiler(new SkillMaterializer(), new HookMaterializer(),
                new CompanyMemoryMaterializer(memories, decisions, semChecklistDoValidador()),
                new ContextPolicy(), manifestStore);
        ContextPackage contextPackage = compiler.compile(new RunContextCompiler.Request(
                new RunContext(companyId, agentId, runId, issueId), workDir, convention, List.of(skill), audience,
                target, "engine:pin", "volume", "prompt vivo", "{\"type\":\"object\"}"));

        assertThat(stored).containsExactly(contextPackage.manifest());
        return new Compilation(contextPackage, written);
    }

    /** Esta suíte é sobre a COMPILAÇÃO do contexto; o checklist do validador (B-ii) fica inerte. */
    private static PlanChecklistSection semChecklistDoValidador() {
        return new PlanChecklistSection(null, null, new PlanValidationProperties());
    }

    private static String executorMemoryWithFeedback() {
        return """
                playbook

                # Feedback do dono sobre esta Issue (revise ANTES de reentregar)

                > As Decisões que o dono ancorou NESTA Issue — inclui o motivo de um REJECT no
                > review. Endereçadas por #N no Log de Decisões. Este re-run precisa ENDEREÇAR
                > este feedback antes de submeter de novo.

                ## Decisões desta Issue
                - #7 [2026-07-16] (produto) corrigir fluxo — racional: faltou a prova
                """;
    }

    private record Compilation(ContextPackage contextPackage, List<String> writtenPaths) {
    }
}
