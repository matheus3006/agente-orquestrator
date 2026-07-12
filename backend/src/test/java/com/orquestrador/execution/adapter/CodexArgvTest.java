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
    void tetoDeSubagenteViraParDeCNativos() {
        // D6 (ADR-0038, requisito de aceite): a fatia amplia autonomia (continuidade nativa) e não
        // shippa sem o teto. No codex o teto é NATIVO e numérico: [agents] max_threads/max_depth via -c.
        assertThat(CodexArgv.build("faça X", "gpt-5.5", "high", IsolationPosture.HOST, null, 6, 3))
                .containsSubsequence("-c", "agents.max_threads=6", "-c", "agents.max_depth=3");
    }

    @Test
    void tetoZeradoOmiteOFlagCorrespondente() {
        // Teto desligado (config vazia/0) omite só o eixo desligado — nunca emite chave sem valor.
        assertThat(CodexArgv.build("t", "gpt-5.5", "high", IsolationPosture.HOST, null, 0, 0))
                .doesNotContain("agents.max_threads=0", "agents.max_depth=0");
        assertThat(CodexArgv.build("t", "gpt-5.5", "high", IsolationPosture.HOST, null, 6, 0))
                .contains("agents.max_threads=6")
                .doesNotContain("agents.max_depth=0");
    }

    @Test
    void tetoConviveComOResumeCirurgicoFlagsAntesDoSubcomando() {
        // O teto é global (-c) ⟹ antes do subcomando resume, como todo flag; prompt segue o último.
        assertThat(CodexArgv.build("grave", "gpt-5.5", "high", IsolationPosture.HOST, "sess-x", 6, 3))
                .containsSubsequence("agents.max_threads=6", "agents.max_depth=3",
                        "resume", "sess-x", "grave");
    }

    @Test
    void outputSchemaViraFlagComCaminhoDeArquivo() {
        // F-N3 (ADR-0039, D2): saída estruturada nativa. Diferente do claude (inline), o codex quer
        // um ARQUIVO (--output-schema <FILE>) — o adapter grava o schema e passa o caminho. Global
        // (funciona também com o subcomando resume, laudo X-8): entra entre os flags, ANTES do prompt.
        assertThat(CodexArgv.build("faça X", "gpt-5.5", "high", IsolationPosture.HOST, null, 0, 0,
                "/tmp/orq-schema.json"))
                .containsSubsequence("--output-schema", "/tmp/orq-schema.json", "faça X");
    }

    @Test
    void schemaNuloOmiteAFlagEConviveComResume() {
        // Sem schema (turno não migrado): omite. Com schema E resume (turno 2+ migrado): coexistem —
        // o --output-schema é global (antes do subcomando resume), a saída é validada na continuada.
        assertThat(CodexArgv.build("t", "gpt-5.5", "high", IsolationPosture.HOST, null, 0, 0, null))
                .doesNotContain("--output-schema");
        assertThat(CodexArgv.build("grave", "gpt-5.5", "high", IsolationPosture.HOST, "sess-x", 6, 3,
                "/tmp/s.json"))
                .containsSubsequence("--output-schema", "/tmp/s.json", "resume", "sess-x", "grave");
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
