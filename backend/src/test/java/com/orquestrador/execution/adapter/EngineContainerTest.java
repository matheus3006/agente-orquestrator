package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget.VolumeMount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O material de container de cada runtime (F.8, ADR-0016): imagem PINADA (defesa contra o --bare
 * virar default do `claude -p`) + o volume de credencial do PLANO montado no home do container
 * (o home inteiro: cobre ~/.claude E ~/.claude.json, que vive fora do dir — deep-research 3-0).
 * A prova unit do isolamento mora aqui: claude e codex não compartilham volume algum.
 */
class EngineContainerTest {

    @Test
    void claudeRodaImagemPinadaComSoACredencialDoPlanoClaude() {
        EngineContainer container = EngineContainer.claudeCode();

        assertThat(container.image()).isEqualTo("orq-claude-code:2.1.201");
        assertThat(container.credentialMounts())
                .containsExactly(new VolumeMount("orq_claude_creds", "/home/node"));
        assertThat(container.credentialFailureHint())
                .contains("orq_claude_creds")
                .contains("setup-claude");
    }

    @Test
    void codexRodaImagemPinadaComSoACredencialDoPlanoCodex() {
        EngineContainer container = EngineContainer.codex();

        assertThat(container.image()).isEqualTo("orq-codex:0.142.5");
        assertThat(container.credentialMounts())
                .containsExactly(new VolumeMount("orq_codex_creds", "/home/node"));
        assertThat(container.credentialFailureHint())
                .contains("orq_codex_creds")
                .contains("setup-codex");
    }

    @Test
    void plainShellNaoCarregaCredencialDePlanoAlgum() {
        EngineContainer container = EngineContainer.plainShell();

        assertThat(container.image()).isEqualTo("alpine:3");
        assertThat(container.credentialMounts()).isEmpty();
    }

    @Test
    void claudeECodexNaoCompartilhamVolumeAlgum() {
        // A prova do isolamento de plano (diferencial #3): o container de um adapter jamais
        // enxerga o volume de credencial do outro.
        var claudeVolumes = EngineContainer.claudeCode().credentialMounts().stream()
                .map(VolumeMount::volumeName).toList();
        var codexVolumes = EngineContainer.codex().credentialMounts().stream()
                .map(VolumeMount::volumeName).toList();

        assertThat(claudeVolumes).doesNotContainAnyElementsOf(codexVolumes);
        assertThat(claudeVolumes).isNotEmpty();
        assertThat(codexVolumes).isNotEmpty();
    }
}
