package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapter rico do `codex` (ADR-0014). Reusa o molde do {@link ClaudeCodeAdapter} com a gramática do Codex:
 * {@link CodexArgv} + {@link CodexStreamParser} + o {@link ExecutionTarget}. {@code provider="openai"} (o
 * `adapterType="codex"` decide o plano ChatGPT). Auto-registra no {@code AdapterRegistry} por {@link #type()}.
 */
@Component
public class CodexAdapter implements AgentAdapter {

    private static final String PROVIDER = "openai";

    /** O container DESTE runtime (F.8): imagem pinada + só a credencial do plano Codex. */
    private static final EngineContainer CONTAINER = EngineContainer.codex();

    @Override
    public EngineContainer engineContainer() {
        return CONTAINER;
    }

    @Override
    public String type() {
        return "codex";
    }

    @Override
    public Result execute(Context context, StepSink steps) {
        if (context.command() == null || context.command().isBlank()) {
            return Result.ok(context.priorSession()); // no-op verde; não gasta token
        }

        List<String> argv = CodexArgv.build(context.command(), context.model(), context.effort(),
                context.target().posture(), context.priorSession());
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                context.workDir(),
                argv,
                context.env() != null ? context.env() : Map.of(),
                CONTAINER.image(),
                context.volumeName(),
                CONTAINER.credentialMounts());

        CodexStreamParser parser = new CodexStreamParser();
        ExecutionTarget.Result run = context.target().run(spec, line -> {
            CodexStreamParser.Step step = parser.accept(line);
            steps.emit(step.type(), step.detail());
        });

        List<CostEmission> costEvents = costEventsFrom(parser, context.model());
        if (run.exitCode() == 0) {
            String session = parser.session() != null ? parser.session() : context.priorSession();
            return Result.ok(session, costEvents);
        }
        String failure = "codex terminou com exit " + run.exitCode()
                + (run.error() != null ? ": " + run.error() : "");
        return Result.fail(withCredentialRemedy(failure, parser.credentialFailure(), context), costEvents);
    }

    /** Peça 4 do ADR-0016 — espelho do remédio do ClaudeCodeAdapter com o volume do plano Codex. */
    private static String withCredentialRemedy(String failure, String credentialSignal, Context context) {
        if (credentialSignal == null) {
            return failure;
        }
        String remedy = context.target().type() == ExecutionTarget.Type.DOCKER
                ? CONTAINER.credentialFailureHint()
                : "credencial do plano Codex indisponivel no host — refaca o login do `codex`"
                        + " (o alvo LOCAL herda o login do host)";
        return remedy + " | sinal: \"" + credentialSignal + "\" | " + failure;
    }

    private static List<CostEmission> costEventsFrom(CodexStreamParser parser, String requestedModel) {
        if (!parser.sawUsage()) {
            return List.of();
        }
        String model = parser.model() != null ? parser.model() : requestedModel;
        return List.of(new CostEmission(PROVIDER, model, parser.tokensInput(), parser.tokensOutput()));
    }
}
