package com.orquestrador.skills;

import com.orquestrador.execution.adapter.WorkspaceConvention;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HookMaterializer (F-N6/ADR-0042): irmão do SkillMaterializer atrás do seam WorkspaceConvention.
 * Escreve o MESMO script guarda + a MESMA forma {"hooks":{"PreToolUse":[...]}} nos dois motores —
 * só o arquivo de configuração muda (settings.json vs hooks.json), confirmado empiricamente contra
 * os CLIs reais (claude 2.1.206 / codex 0.142.5) nesta faixa.
 */
class HookMaterializerTest {

    private final HookMaterializer materializer = new HookMaterializer();

    @TempDir
    Path workDir;

    @Test
    void materializaOScriptGuardaEOConfigParaAConvencaoClaude() throws IOException {
        materializer.materialize(workDir, WorkspaceConvention.claude());

        assertThat(Files.exists(workDir.resolve(".claude/hooks/pretooluse-guard.js"))).isTrue();
        String config = Files.readString(workDir.resolve(".claude/settings.json"));
        assertThat(config).contains("\"PreToolUse\"")
                .contains("\"command\": \"node .claude/hooks/pretooluse-guard.js\"");
    }

    @Test
    void materializaOMesmoScriptGuardaParaAConvencaoCodex() throws IOException {
        // F-N6: a MESMA forma {"hooks":{"PreToolUse":[...]}} vale nos 2 motores — só o arquivo
        // muda (hooks.json dedicado, não settings.json) e o script é byte-a-byte o mesmo.
        materializer.materialize(workDir, WorkspaceConvention.codex());

        String scriptCodex = Files.readString(workDir.resolve(".codex/hooks/pretooluse-guard.js"));
        assertThat(scriptCodex).isEqualTo(HookMaterializer.GUARD_SCRIPT);
        String config = Files.readString(workDir.resolve(".codex/hooks.json"));
        assertThat(config).contains("\"PreToolUse\"")
                .contains("\"command\": \"node .codex/hooks/pretooluse-guard.js\"");
        assertThat(Files.exists(workDir.resolve(".claude"))).isFalse();
    }

    @Test
    void idempotenteReescreveOsDoisArquivos() throws IOException {
        materializer.materialize(workDir, WorkspaceConvention.claude());
        materializer.materialize(workDir, WorkspaceConvention.claude());

        assertThat(Files.readString(workDir.resolve(".claude/hooks/pretooluse-guard.js")))
                .isEqualTo(HookMaterializer.GUARD_SCRIPT);
    }
}
