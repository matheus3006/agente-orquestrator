package com.orquestrador.common.security;

public enum ActorType {
    BOARD,
    AGENT,
    // O PIPELINE em si (F.12/ADR-0026): atribuicao verdadeira de um ato de sistema sem operador nem
    // agente por tras — ex.: o WorkspaceReaper coletando um workspace. Nunca autentica requisicao
    // (o CompanyAccessGuard nega SYSTEM por construcao); so aparece como ator no activity_log.
    SYSTEM
}
