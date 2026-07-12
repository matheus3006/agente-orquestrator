package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * O teto NATIVO de subagentes do D6 (ADR-0038, requisito de aceite): {@code [agents]
     * max_threads}/{@code max_depth}, embarcado em TODO run do codex (CEO e executor). Config de
     * produto (default 6/3), não da CLI — o eixo 0 desliga. A fatia amplia autonomia e não shippa sem ele.
     */
    private final int maxThreads;
    private final int maxDepth;

    public CodexAdapter(
            @Value("${orquestrador.ceiling.codex.max-threads:6}") int maxThreads,
            @Value("${orquestrador.ceiling.codex.max-depth:3}") int maxDepth) {
        this.maxThreads = maxThreads;
        this.maxDepth = maxDepth;
    }

    @Override
    public EngineContainer engineContainer() {
        return CONTAINER;
    }

    /** O codex não lê {@code .claude/} — carta em {@code AGENTS.md}, skills em {@code .codex/skills} (ADR-0032). */
    @Override
    public WorkspaceConvention workspaceConvention() {
        return WorkspaceConvention.codex();
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

        // F-N3 (ADR-0039, D2): a saída estruturada nativa. O codex exige o schema num ARQUIVO
        // (--output-schema <FILE>, diferente do claude inline) — grava-se em .orq/ do workspace, que
        // é o cwd do processo no LOCAL (v1). Falha de I/O = falha LEGÍVEL do run (nunca schema mudo).
        // Nulo ⟹ turno não migrado: sem arquivo, sem flag, saída em prosa (o caminho pré-F-N3).
        String outputSchemaFile;
        try {
            outputSchemaFile = writeSchemaFile(context.workDir(), context.outputSchema());
        } catch (IOException falhaAoGravar) {
            return Result.fail("codex: falha ao gravar o schema de saída do turno: " + falhaAoGravar.getMessage());
        }
        List<String> argv = CodexArgv.build(context.command(), context.model(), context.effort(),
                context.target().posture(), context.priorSession(), maxThreads, maxDepth, outputSchemaFile);
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                context.workDir(),
                argv,
                context.env() != null ? context.env() : Map.of(),
                CONTAINER.image(),
                context.volumeName(),
                CONTAINER.credentialMounts());

        CodexStreamParser parser = new CodexStreamParser();
        ExecutionTarget.Result run = context.target().run(spec, context.runId(), line -> {
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
        String remediado = withCredentialRemedy(failure, parser.credentialFailure(), context);
        // F-N2 (ADR-0038): resumiu (priorSession) E a sessão sumiu ⟹ sessionLost — fresh start
        // barulhento no executor (RUN_SESSION_LOST). Sem resume, o sinal não vale (guarda dupla).
        if (parser.sessionLost() && resumed(context)) {
            return Result.failSessionLost(remediado, costEvents);
        }
        return Result.fail(remediado, costEvents);
    }

    /**
     * Grava o schema do turno em {@code .orq/turn-schema.json} do workspace e devolve o caminho
     * absoluto para o {@code --output-schema}. O {@code .orq/} é protocolo do produto (fora do
     * replace de materialização, ADR-0030/M6) e no LOCAL é o cwd do processo. Schema nulo/branco ⟹
     * devolve null (turno não migrado): nenhum arquivo, nenhuma flag. (DOCKER: o arquivo precisa
     * chegar ao volume — follow-up da fatia; no LOCAL do v1 o workDir É o workspace.)
     */
    private static String writeSchemaFile(Path workDir, String outputSchema) throws IOException {
        if (outputSchema == null || outputSchema.isBlank()) {
            return null;
        }
        Path schemaFile = workDir.resolve(".orq").resolve("turn-schema.json");
        Files.createDirectories(schemaFile.getParent());
        Files.writeString(schemaFile, outputSchema);
        return schemaFile.toString();
    }

    /** Este run tentou religar uma sessão nativa (F-N2)? Só então o sinal de sessão perdida vale. */
    private static boolean resumed(Context context) {
        return context.priorSession() != null && !context.priorSession().isBlank();
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
