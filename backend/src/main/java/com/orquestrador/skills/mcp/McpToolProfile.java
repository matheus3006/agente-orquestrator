package com.orquestrador.skills.mcp;

/**
 * QUAIS tools o servidor MCP-stdio anuncia (e o core serve) numa sessão — o trilho MCP abre nas DUAS
 * PONTAS a partir da F-C3 (C10), e cada ponta enxerga o seu conjunto:
 *
 * <ul>
 *   <li>{@link #EXECUTOR} — run de Issue: {@code knowall_search} + {@code knowall_fetch_body}
 *       (F-AT2) <b>e</b> {@code memory_search} + {@code memory_fetch} (F-C3).</li>
 *   <li>{@link #CEO} — turno de conversa: <b>SÓ</b> as tools de memória. O corpo de skill do KnowAll
 *       segue EXCLUSIVO do executor: a F-AT2/D6 decidiu "personas FORA" desse trilho (o CEO PROPÕE
 *       skill pelo gate — propose-then-commit), e abrir a 2ª ponta da memória não pode ampliar a
 *       autonomia da persona por efeito colateral.</li>
 * </ul>
 *
 * <p>O perfil viaja por ARGV até o script (que anuncia só as tools da ponta) <b>e</b> mora no
 * {@link McpRunScope} (que o core consulta): o script é relay burro — quem RECUSA uma tool fora do
 * perfil é o {@code KnowAllMcpBridge}, porque o agente pode chamar uma tool que ninguém anunciou.
 */
public enum McpToolProfile {

    EXECUTOR("executor"),
    CEO("ceo");

    private final String argv;

    McpToolProfile(String argv) {
        this.argv = argv;
    }

    /** O valor que o servidor MCP recebe em {@code argv[4]} — estável, minúsculo, sem acento. */
    public String argv() {
        return argv;
    }
}
