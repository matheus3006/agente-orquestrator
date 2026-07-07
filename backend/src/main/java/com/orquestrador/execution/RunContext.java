package com.orquestrador.execution;

import java.util.UUID;

/** O que um Run precisa para executar. O {@code runId} e a chave que vai para o checkout da Issue. */
public record RunContext(UUID companyId, UUID agentId, UUID runId, UUID issueId) {
}
