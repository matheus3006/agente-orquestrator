package com.orquestrador.skills.mcp;

import java.util.UUID;

/**
 * O escopo de um Run de EXECUTOR por trás de uma sessão do {@link KnowAllMcpBridge} (F-AT2, grill
 * agente-como-tool D6): a company que enquadra a busca, o agente/issue/run que a auditoria carimba.
 * O token efêmero da sessão (não este escopo) é o que viaja no argv do servidor MCP — o escopo fica
 * SÓ no core, resolvido a partir do token; o agente nunca o vê (identidade do produto, não do agente).
 */
public record McpRunScope(UUID companyId, UUID agentId, UUID issueId, UUID runId) {
}
