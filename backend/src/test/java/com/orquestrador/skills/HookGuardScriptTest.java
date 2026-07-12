package com.orquestrador.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O script guarda materializado pelo {@link HookMaterializer} (F-N6/ADR-0042) executado de
 * verdade via {@code node} (garantido no PATH — os 2 CLIs claude/codex são pacotes npm, e o
 * monorepo já depende de node para o front): prova a LÓGICA de decisão — deny (a) skills
 * read-only, deny (b) cinto de sessão, caminho feliz — sem precisar dos CLIs pagos claude/codex
 * (isso fica pro /verify com VENDO). Determinístico e rápido: cabe na suíte automática.
 */
class HookGuardScriptTest {

    private static final String HOME = System.getProperty("user.home");

    @TempDir
    Path workDir;

    private Path scriptPath;

    @BeforeEach
    void materializaOScript() throws IOException {
        scriptPath = workDir.resolve("guard.js");
        Files.writeString(scriptPath, HookMaterializer.GUARD_SCRIPT, StandardCharsets.UTF_8);
    }

    private int roda(String stdinJson) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("node", scriptPath.toString());
        pb.directory(workDir.toFile());
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
        }
        boolean terminou = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(terminou).as("processo node terminou a tempo").isTrue();
        return process.exitValue();
    }

    @Test
    void negaWriteDentroDoDiretorioDeSkillsDoClaude() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Write",
                 "tool_input": {"file_path": "%s/.claude/skills/tdd/SKILL.md", "content": "x"}}
                """.formatted(workDir, workDir);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void negaEditDentroDoDiretorioDeSkillsDoCodex() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Edit",
                 "tool_input": {"file_path": "%s/.codex/skills/tdd/SKILL.md",
                                 "old_string": "a", "new_string": "b"}}
                """.formatted(workDir, workDir);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void negaApplyPatchAddFileDentroDoDiretorioDeSkills() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "apply_patch",
                 "tool_input": {"command": "*** Begin Patch\\n*** Add File: .codex/skills/tdd/SKILL.md\\n+x\\n*** End Patch\\n"}}
                """.formatted(workDir);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void permiteWriteForaDoDiretorioDeSkills() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Write",
                 "tool_input": {"file_path": "%s/hello.txt", "content": "hi"}}
                """.formatted(workDir, workDir);

        assertThat(roda(json)).isZero();
    }

    @Test
    void permiteApplyPatchAddFileForaDoDiretorioDeSkills() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "apply_patch",
                 "tool_input": {"command": "*** Begin Patch\\n*** Add File: hello.txt\\n+hi\\n*** End Patch\\n"}}
                """.formatted(workDir);

        assertThat(roda(json)).isZero();
    }

    @Test
    void permiteLerUmaSkill() throws Exception {
        // As skills DEVEM continuar legíveis — D6 é só read-only (nega escrita), não isolamento de leitura.
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Read",
                 "tool_input": {"file_path": "%s/.claude/skills/tdd/SKILL.md"}}
                """.formatted(workDir, workDir);

        assertThat(roda(json)).isZero();
    }

    @Test
    void negaLerODiretorioDeSessaoDoClaudeForaDoWorkspace() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Read",
                 "tool_input": {"file_path": "%s/.claude/projects/algum-projeto/sessao.jsonl"}}
                """.formatted(workDir, HOME);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void negaBashLendoODiretorioDeSessaoDoCodexForaDoWorkspace() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "cat %s/.codex/sessions/2026/07/12/rollout.jsonl"}}
                """.formatted(workDir, HOME);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void negaBashLendoODiretorioDeSessaoViaTil() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "cat ~/.claude/projects/algum/sessao.jsonl"}}
                """.formatted(workDir);

        assertThat(roda(json)).isEqualTo(2);
    }

    @Test
    void permiteBashComComandoQueNaoTocaSessao() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Bash",
                 "tool_input": {"command": "pwd; ls -la"}}
                """.formatted(workDir);

        assertThat(roda(json)).isZero();
    }

    @Test
    void permiteToolNaoRastreadaComoGrep() throws Exception {
        String json = """
                {"cwd": "%s", "hook_event_name": "PreToolUse", "tool_name": "Grep",
                 "tool_input": {"pattern": "foo"}}
                """.formatted(workDir);

        assertThat(roda(json)).isZero();
    }

    @Test
    void payloadIlegivelFailOpenNuncaTravaORun() throws Exception {
        assertThat(roda("isto nao e json")).isZero();
    }
}
