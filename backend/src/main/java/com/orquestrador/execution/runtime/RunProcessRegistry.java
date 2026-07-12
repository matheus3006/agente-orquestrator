package com.orquestrador.execution.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O processo NATIVO vivo de um Run, por runId (F-N4/ADR-0040, D4) — colaborador interno do
 * {@link LocalExecutionTarget} (molde do campo {@code previewServers} da mesma classe: mapa
 * privado, nao um bean Spring proprio — ninguem mais precisa injeta-lo, so o alvo que o possui).
 * Fecha o requisito duro da sonda R6-B: o filho da tool call (build, subprocesso do codex,
 * servidor) sobrevive ORFAO a um SIGKILL so-do-pai, nos dois motores. {@code run} registra o
 * {@link Process} ao nascer e desregistra ao terminar; o endpoint de interrupt chama
 * {@link #interrupt(UUID)} de outra thread (a requisicao HTTP), enquanto a thread do dispatcher
 * segue bloqueada dentro do {@code run} sincrono.
 *
 * <p>{@link #interrupt(UUID)} mata os DESCENDENTES antes do processo raiz — o mesmo molde que
 * {@code LocalExecutionTarget.stopPreviewServer} ja usa para servidores de preview (capturar
 * {@code descendants()} ANTES de destruir o pai; depois de morto, a arvore de processos some e
 * a enumeracao ficaria vazia).
 */
public class RunProcessRegistry {

    private final Map<UUID, Process> live = new ConcurrentHashMap<>();

    public void register(UUID runId, Process process) {
        live.put(runId, process);
    }

    public void unregister(UUID runId) {
        live.remove(runId);
    }

    /** true = achou um processo vivo para este runId e matou (descendentes + raiz). */
    public boolean interrupt(UUID runId) {
        Process process = live.get(runId);
        if (process == null) {
            return false;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return true;
    }
}
