package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget.IsolationPosture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gramática do CLI `codex` (ADR-0014): argv puro ao ProcessBuilder (sem shell). Diferente do `claude`, o
 * esforço vai num par `-c model_reasoning_effort=<effort>` (sem flag dedicada, sem caso ultracode) e o prompt
 * é POSICIONAL no fim. null → omite cada flag.
 */
class CodexArgvTest {

    @Test
    void montaArgvCompletoComModelEEffortEPromptNoFim() {
        // HOST (F.11/ADR-0025): sem sandbox externo o codex se auto-sandboxa em workspace-write
        // (bypass no host foi RECUSADO — seria exec arbitrario do modelo com permissao do operador).
        assertThat(CodexArgv.build("faça X", "gpt-5.5", "high", IsolationPosture.HOST))
                .containsExactly("codex", "exec", "--json",
                        "--model", "gpt-5.5", "-c", "model_reasoning_effort=high",
                        "--skip-git-repo-check", "-s", "workspace-write", "faça X");
    }

    @Test
    void modelNuloOmiteAFlag() {
        assertThat(CodexArgv.build("t", null, "high", IsolationPosture.HOST)).doesNotContain("--model");
    }

    @Test
    void effortNuloOmiteOParametroC() {
        assertThat(CodexArgv.build("t", "gpt-5.5", null, IsolationPosture.HOST)).doesNotContain("-c");
    }

    @Test
    void ambosNulosDeixamBasePosturaEPrompt() {
        assertThat(CodexArgv.build("faça X", null, null, IsolationPosture.HOST))
                .containsExactly("codex", "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write",
                        "faça X");
    }

    @Test
    void brancoEhTratadoComoNulo() {
        assertThat(CodexArgv.build("t", "  ", "  ", IsolationPosture.HOST))
                .containsExactly("codex", "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write",
                        "t");
    }

    @Test
    void hostJamaisGanhaOBypass() {
        assertThat(CodexArgv.build("t", null, null, IsolationPosture.HOST))
                .doesNotContain("--dangerously-bypass-approvals-and-sandbox");
    }

    @Test
    void resumeCirurgicoUsaOSubcomandoResumeComSessionIdEPromptPosicionais() {
        // M9 (ADR-0030): a rede resume-cirúrgico religa a MESMA sessão. No codex 0.142.5 resume NÃO é
        // flag — é SUBCOMANDO com SESSION_ID e PROMPT posicionais (codex-rs/exec/src/cli.rs @
        // rust-v0.142.5: Command::Resume linha 168, posicionais 180-181/202-203). `-s` não é global
        // (fora do mark_exec_global_args, cli.rs:157-163) ⟹ TODO flag antes do subcomando, na forma
        // que o próprio source crava no override_usage (cli.rs:12): `codex exec [OPTIONS] <COMMAND> [ARGS]`.
        assertThat(CodexArgv.build("grave o bilhete", "gpt-5.5", "high", IsolationPosture.HOST, "sess-abc"))
                .containsExactly("codex", "exec", "--json",
                        "--model", "gpt-5.5", "-c", "model_reasoning_effort=high",
                        "--skip-git-repo-check", "-s", "workspace-write",
                        "resume", "sess-abc", "grave o bilhete");
    }

    @Test
    void runNormalNaoGanhaOSubcomandoResume() {
        // Todo run normal (D4): priorSession=null ⟹ sessão fresca, sem religar.
        assertThat(CodexArgv.build("t", "gpt-5.5", "high", IsolationPosture.HOST, null))
                .doesNotContain("resume", "--resume");
    }

    @Test
    void externamenteSandboxadoGanhaSkipGitEBypassComPromptNoFim() {
        // F.11 (ADR-0025): o container E o sandbox externo — o codex roda sem self-sandbox.
        // Flags empiricamente confirmados contra o codex 0.142.5; prompt segue o ULTIMO.
        assertThat(CodexArgv.build("faça X", "gpt-5.5", "high", IsolationPosture.EXTERNALLY_SANDBOXED))
                .containsExactly("codex", "exec", "--json",
                        "--model", "gpt-5.5", "-c", "model_reasoning_effort=high",
                        "--skip-git-repo-check", "--dangerously-bypass-approvals-and-sandbox", "faça X");
    }
}
