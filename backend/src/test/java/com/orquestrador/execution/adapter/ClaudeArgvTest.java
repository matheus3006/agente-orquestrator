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
    void denylistDeToolsViraDisallowedTools() {
        // D6 (ADR-0038, requisito de aceite): a fatia amplia autonomia e não shippa sem o teto. No
        // claude o aninhamento de subagente já é capado NATIVAMENTE em profundidade 5 (laudo [C-3]);
        // este seam é o lever de PRODUTO — nega tools por regra nativa (default vazio = só o cap nativo).
        List<String> argv = ClaudeArgv.build("faça X", "opus", "high", null, "Agent Task");
        int i = argv.indexOf("--disallowedTools");
        assertThat(i).isPositive();
        assertThat(argv.get(i + 1)).isEqualTo("Agent Task");
    }

    @Test
    void denylistVaziaOuNulaOmiteAFlag() {
        // Default (vazio): nenhuma flag — o teto ativo é o cap nativo de profundidade 5 do claude.
        assertThat(ClaudeArgv.build("t", "opus", "high", null, null)).doesNotContain("--disallowedTools");
        assertThat(ClaudeArgv.build("t", "opus", "high", null, "  ")).doesNotContain("--disallowedTools");
    }

    @Test
    void denylistConviveComOResumeCirurgico() {
        List<String> argv = ClaudeArgv.build("destile", "opus", "high", "sess-abc", "Agent");
        assertThat(argv).contains("--disallowedTools", "Agent", "--resume", "sess-abc");
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

    @Test
    void outputSchemaViraJsonSchemaInline() {
        // F-N3 (ADR-0039, D2): a saída estruturada nativa. O schema entra INLINE (o claude aceita a
        // string direta em --json-schema — provado ao vivo) e prende o {resposta, blocos[]} do turno.
        String schema = "{\"type\":\"object\"}";
        List<String> argv = ClaudeArgv.build("faça X", "opus", "high", null, null, schema);
        int i = argv.indexOf("--json-schema");
        assertThat(i).isPositive();
        assertThat(argv.get(i + 1)).isEqualTo(schema);
    }

    @Test
    void schemaNuloOmiteAFlagEConviveComResume() {
        // Sem schema (turno não migrado / persona legada): nenhuma flag. Com schema E resume (turno
        // 2+ de persona migrada): os dois coexistem — a saída é validada mesmo na sessão continuada.
        assertThat(ClaudeArgv.build("t", "opus", "high", null, null, null)).doesNotContain("--json-schema");
        assertThat(ClaudeArgv.build("t", "opus", "high", "sess-x", null, "{\"type\":\"object\"}"))
                .contains("--json-schema", "--resume", "sess-x");
    }

    @Test
    void mcpConfigViraMcpConfigMaisStrict() {
        // F-AT2 (grill agente-como-tool D2, laudo sonda MCP-consume R12-B): o servidor MCP do KnowAll
        // entra por --mcp-config <arquivo> + --strict-mcp-config (isola o run para ver SÓ este servidor).
        List<String> argv = ClaudeArgv.build("faça X", "opus", "high", null, null, null,
                "/x/.orq/mcp/claude-mcp.json");
        int i = argv.indexOf("--mcp-config");
        assertThat(i).isPositive();
        assertThat(argv.get(i + 1)).isEqualTo("/x/.orq/mcp/claude-mcp.json");
        assertThat(argv).contains("--strict-mcp-config");
    }

    @Test
    void semMcpConfigOmiteAsFlags() {
        assertThat(ClaudeArgv.build("t", "opus", "high", null, null, null, null))
                .doesNotContain("--mcp-config", "--strict-mcp-config");
        assertThat(ClaudeArgv.build("t", "opus", "high", null, null, null, "  "))
                .doesNotContain("--mcp-config", "--strict-mcp-config");
    }

    @Test
    void mcpConviveComSchemaEResumeCirurgico() {
        List<String> argv = ClaudeArgv.build("destile", "opus", "high", "sess-x", null,
                "{\"type\":\"object\"}", "/x/mcp.json");
        assertThat(argv).contains("--mcp-config", "/x/mcp.json", "--strict-mcp-config",
                "--json-schema", "--resume", "sess-x");
    }
}
