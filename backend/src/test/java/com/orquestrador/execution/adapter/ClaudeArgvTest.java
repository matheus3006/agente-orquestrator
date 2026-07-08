package com.orquestrador.execution.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gramática do CLI `claude` (ADR-0014): o argv puro passado ao ProcessBuilder (sem shell — o prompt é
 * UM elemento, sem quoting/injeção). Esforço mora em lugares diferentes: `--effort` para os níveis, mas
 * `ultracode` NÃO é effort — vira `--settings {"ultracode":true}` (JSON cru, sem aspas de shell). null → omite.
 */
class ClaudeArgvTest {

    @Test
    void montaArgvCompletoComModelEEffort() {
        assertThat(ClaudeArgv.build("faça X", "opus", "high"))
                .containsExactly("claude", "-p", "faça X",
                        "--output-format", "stream-json", "--verbose",
                        "--permission-mode", "bypassPermissions",
                        "--model", "opus", "--effort", "high");
    }

    @Test
    void ultracodeViraSettingsNaoEffort() {
        List<String> argv = ClaudeArgv.build("t", "opus", "ultracode");
        int i = argv.indexOf("--settings");
        assertThat(i).isPositive();
        assertThat(argv.get(i + 1)).isEqualTo("{\"ultracode\":true}");
        assertThat(argv).doesNotContain("--effort");
    }

    @Test
    void modelNuloOmiteAFlag() {
        assertThat(ClaudeArgv.build("t", null, "high")).doesNotContain("--model");
    }

    @Test
    void effortNuloOmiteAsFlags() {
        assertThat(ClaudeArgv.build("t", "opus", null)).doesNotContain("--effort", "--settings");
    }

    @Test
    void ambosNulosDeixamSoOBase() {
        assertThat(ClaudeArgv.build("faça X", null, null))
                .containsExactly("claude", "-p", "faça X", "--output-format", "stream-json", "--verbose",
                        "--permission-mode", "bypassPermissions");
    }

    @Test
    void brancoEhTratadoComoNulo() {
        assertThat(ClaudeArgv.build("t", "   ", "  "))
                .containsExactly("claude", "-p", "t", "--output-format", "stream-json", "--verbose",
                        "--permission-mode", "bypassPermissions");
    }

    @Test
    void priorSessionLigaOResumeCirurgico() {
        // M9 (ADR-0030): a rede resume-cirúrgico religa a MESMA sessão só para destilar.
        List<String> argv = ClaudeArgv.build("destile", "opus", "high", "sess-abc123");
        int i = argv.indexOf("--resume");
        assertThat(i).isPositive();
        assertThat(argv.get(i + 1)).isEqualTo("sess-abc123");
    }

    @Test
    void semPriorSessionNaoLigaResume() {
        // Todo run normal (D4): --resume desligado para CONTINUAR o trabalho.
        assertThat(ClaudeArgv.build("t", "opus", "high")).doesNotContain("--resume");
        assertThat(ClaudeArgv.build("t", "opus", "high", null)).doesNotContain("--resume");
    }
}
