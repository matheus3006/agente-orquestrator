package com.orquestrador.execution.adapter;

import java.util.List;

/**
 * O registro AD-HOC de UM servidor MCP-stdio que um Run de EXECUTOR deve enxergar (F-AT2, grill
 * agente-como-tool D2, gramática do laudo da sonda MCP-consume R12-B). É o que o {@code command}+
 * {@code args} materializam em cada motor: o CLI spawna {@code command args...} como subprocesso e
 * fala MCP por stdio com ele. Config 100% por invocação (nada persiste em {@code ~/.claude.json}/
 * {@code ~/.codex/config.toml}) — o {@code ClaudeArgv} vira um arquivo {@code --mcp-config}; o
 * {@code CodexArgv} vira os pares {@code -c mcp_servers.<name>.command/.args}.
 *
 * <p>No F-AT2 o {@code command} é {@code node} e os {@code args} são {@code [scriptDoServidor,
 * baseUrlDoCallback, tokenEfêmeroDoRun]} — o token (não credencial de agente) é o que liga o servidor
 * MCP ao {@code KnowAllMcpBridge}; sem credencial no {@code env}, sem rota REST ator-AGENT (D2).
 */
public record McpServerSpec(String name, String command, List<String> args) {

    public McpServerSpec {
        args = (args == null) ? List.of() : List.copyOf(args);
    }
}
