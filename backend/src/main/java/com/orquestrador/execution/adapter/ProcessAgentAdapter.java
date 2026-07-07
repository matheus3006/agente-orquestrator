package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapter generico "process": roda um comando de shell no Workspace via o ExecutionTarget
 * escolhido (local/docker) e transforma cada linha de stdout num passo de StepTrace.
 * E o arquetipo bash/process; adapters ricos (Claude Code/Codex) seguem o mesmo contrato.
 */
@Component
public class ProcessAgentAdapter implements AgentAdapter {

    /** Shell puro (F.8): sem plano de assinatura — nada a montar, nada a expirar. */
    private static final EngineContainer CONTAINER = EngineContainer.plainShell();

    @Override
    public EngineContainer engineContainer() {
        return CONTAINER;
    }

    @Override
    public String type() {
        return "process";
    }

    /** Prosa de turno em {@code sh -c} seria EXECUTADA como shell: aqui o prompt vem só pelo env. */
    @Override
    public boolean acceptsPromptAsCommand() {
        return false;
    }

    @Override
    public Result execute(Context context, StepSink steps) {
        if (context.command() == null || context.command().isBlank()) {
            // Sem comando: no-op bem-sucedido (mantem o loop verde para agentes ainda nao configurados).
            return Result.ok(context.priorSession());
        }
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                context.workDir(),
                List.of("sh", "-c", context.command()),
                context.env() != null ? context.env() : Map.of(),
                CONTAINER.image(),
                context.volumeName(),
                CONTAINER.credentialMounts());
        ExecutionTarget.Result result = context.target().run(spec, line -> steps.emit("STDOUT", line));
        if (result.exitCode() == 0) {
            return Result.ok(context.priorSession());
        }
        return Result.fail("processo terminou com exit " + result.exitCode()
                + (result.error() != null ? ": " + result.error() : ""));
    }
}
