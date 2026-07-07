package com.orquestrador.execution;

/**
 * Executa o trabalho de um Run. Na slice 2 e um stub (faz o checkout da Issue com o runId e conclui);
 * na slice 3 vira a invocacao real do {@code AgentAdapter.execute()} num container (ADR-0004).
 */
public interface RunExecutor {

    RunOutcome execute(RunContext runContext);
}
