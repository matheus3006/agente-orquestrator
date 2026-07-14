package com.orquestrador.skills.mcp;

import java.util.UUID;

/**
 * O escopo de uma sessão do {@link KnowAllMcpBridge} (F-AT2, grill agente-como-tool D6; ampliado na
 * F-C3/C10): a company que enquadra a busca, o agente/issue/run que a auditoria carimba e o
 * {@link McpToolProfile} da PONTA. O token efêmero da sessão (não este escopo) é o que viaja no argv
 * do servidor MCP — o escopo fica SÓ no core, resolvido a partir do token; o agente nunca o vê
 * (identidade do produto, não do agente).
 *
 * <p><b>{@code issueId} é NULO no turno do CEO</b> (C10): o trilho da memória abre nas duas pontas, e
 * a do CEO — o consumidor primário do <i>"por que decidimos #7?"</i> — não tem Issue. Só a company
 * enquadra a leitura de memória; a Issue segue carimbando a auditoria quando existe (run de executor).
 */
public record McpRunScope(UUID companyId, UUID agentId, UUID issueId, UUID runId, McpToolProfile profile) {
}
