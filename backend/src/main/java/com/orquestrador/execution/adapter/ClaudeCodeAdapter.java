package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter rico do `claude` (ADR-0014, MARCO do padrão). Costura três módulos profundos: {@link ClaudeArgv}
 * (a gramática do CLI), {@link ClaudeStreamParser} (o stream-json → steps semânticos + tokens) e o
 * {@link ExecutionTarget} (ONDE roda — LOCAL no v1, herdando o login do host). O adapter em si é raso: monta
 * o Spec, roda alimentando o parser linha-a-linha, e traduz o resultado no contrato do seam. Auto-registra
 * no {@code AdapterRegistry} por {@link #type()}.
 *
 * <p>Custo (Q7): emite {@link CostEmission} com {@code provider="anthropic"}, o model REAL resolvido do
 * stream e os tokens; o executor roteia com {@code costCents=0} (assinatura → hard-stop de dólares inócuo).
 * Falha: {@code fail(error, costEvents)} — os tokens gastos antes de terminar contam (ADR-0015).
 */
@Component
public class ClaudeCodeAdapter implements AgentAdapter {

    private static final String PROVIDER = "anthropic";

    /** O container DESTE runtime (F.8): imagem pinada + só a credencial do plano Claude. */
    private static final EngineContainer CONTAINER = EngineContainer.claudeCode();

    @Override
    public EngineContainer engineContainer() {
        return CONTAINER;
    }

    /**
     * Alternativa de automação ao login-no-volume (ADR-0016): o token do `claude setup-token`
     * (~1 ano) entra como env {@code CLAUDE_CODE_OAUTH_TOKEN}. Branco = só o login do volume vale.
     */
    private final String setupOauthToken;

    /**
     * O teto de subagentes do D6 (ADR-0038, requisito de aceite) no claude: a denylist de tools
     * por regra nativa ({@code --disallowedTools "Agent Task"}). Diferente do codex (número), o
     * aninhamento do claude já é capado NATIVAMENTE em profundidade 5 (laudo [C-3]) — este seam é o
     * lever de PRODUTO, default VAZIO (só o cap nativo vale; a fatia não rebaixa a barra). Pronto
     * para o F-N6 (skills read-only) e para tuning do operador sem tocar código.
     */
    private final String disallowedTools;

    public ClaudeCodeAdapter(
            @Value("${orquestrador.engines.claude.oauth-token:}") String setupOauthToken,
            @Value("${orquestrador.ceiling.claude.disallowed-tools:}") String disallowedTools) {
        this.setupOauthToken = setupOauthToken;
        this.disallowedTools = disallowedTools;
    }

    @Override
    public String type() {
        return "claude-code";
    }

    @Override
    public Result execute(Context context, StepSink steps) {
        if (context.command() == null || context.command().isBlank()) {
            // Sem prompt: no-op verde (mantém o loop de agentes ainda não configurados). Não gasta token.
            return Result.ok(context.priorSession());
        }

        // F-N3 (ADR-0039, D2): a saída estruturada nativa entra INLINE (--json-schema); o JSON
        // validado do turno volta no campo `result` do stream (que o parser já captura). Nulo ⟹
        // turno não migrado, o argv omite a flag e a saída segue em prosa (o caminho pré-F-N3).
        List<String> argv = ClaudeArgv.build(context.command(), context.model(), context.effort(),
                context.priorSession(), disallowedTools, context.outputSchema());
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                context.workDir(),
                argv,
                envWithSetupToken(context.env()),
                CONTAINER.image(),
                context.volumeName(),
                CONTAINER.credentialMounts());

        ClaudeStreamParser parser = new ClaudeStreamParser();
        ExecutionTarget.Result run = context.target().run(spec, context.runId(), line -> {
            ClaudeStreamParser.Step step = parser.accept(line);
            steps.emit(step.type(), step.detail());
        });

        List<CostEmission> costEvents = costEventsFrom(parser, context.model());
        if (run.exitCode() == 0) {
            String session = parser.session() != null ? parser.session() : context.priorSession();
            return Result.ok(session, costEvents);
        }
        // Fail-soft: exit != 0 vira falha da Run (o erro tipado model_refusal/transient é fatia futura,
        // ADR-0004). Os tokens já lidos VÃO junto (a factory de falha da fatia B os carrega).
        String failure = "claude terminou com exit " + run.exitCode()
                + (run.error() != null ? ": " + run.error() : "");
        String remediado = withCredentialRemedy(failure, parser.credentialFailure(), context);
        // F-N2 (ADR-0038): resumiu (priorSession) E a sessão sumiu ⟹ sessionLost — o executor faz
        // fresh start barulhento (RUN_SESSION_LOST). Guarda dupla: sem resume, o sinal não vale.
        if (parser.sessionLost() && resumed(context)) {
            return Result.failSessionLost(remediado, costEvents);
        }
        return Result.fail(remediado, costEvents);
    }

    /**
     * Peça 4 do ADR-0016: sinal de credencial quebrada antepõe o REMÉDIO ao erro — no DOCKER o
     * volume + script de setup (do {@link EngineContainer}); no LOCAL o login do próprio host.
     */
    private static String withCredentialRemedy(String failure, String credentialSignal, Context context) {
        if (credentialSignal == null) {
            return failure;
        }
        String remedy = context.target().type() == ExecutionTarget.Type.DOCKER
                ? CONTAINER.credentialFailureHint()
                : "credencial do plano Claude indisponivel no host — refaca o login do `claude`"
                        + " (o alvo LOCAL herda o login do host)";
        return remedy + " | sinal: \"" + credentialSignal + "\" | " + failure;
    }

    /** Este run tentou religar uma sessão nativa (F-N2)? Só então o sinal de sessão perdida vale. */
    private static boolean resumed(Context context) {
        return context.priorSession() != null && !context.priorSession().isBlank();
    }

    /** Preserva o env do executor (ORQ_PROMPT) e soma o setup-token quando configurado. */
    private Map<String, String> envWithSetupToken(Map<String, String> contextEnv) {
        Map<String, String> env = contextEnv != null ? contextEnv : Map.of();
        if (setupOauthToken == null || setupOauthToken.isBlank()) {
            return env;
        }
        Map<String, String> envComToken = new HashMap<>(env);
        envComToken.put("CLAUDE_CODE_OAUTH_TOKEN", setupOauthToken.trim());
        return Map.copyOf(envComToken);
    }

    /**
     * Uma emissão do turno principal (só se o stream trouxe usage) + uma SEGUNDA off-book (F-N5/
     * ADR-0041, D5) se algum subagente rodou — {@code task_notification} soma tokens que o usage
     * do turno NUNCA inclui (laudo, Sonda 1). Model REAL do stream, senão o pedido no motor.
     */
    private static List<CostEmission> costEventsFrom(ClaudeStreamParser parser, String requestedModel) {
        List<CostEmission> emissions = new ArrayList<>();
        String model = parser.model() != null ? parser.model() : requestedModel;
        if (parser.sawUsage()) {
            emissions.add(new CostEmission(PROVIDER, model, parser.tokensInput(), parser.tokensOutput()));
        }
        if (parser.subagentTokens() > 0) {
            // claude só expõe o TOTAL do subagente (task_notification.usage.total_tokens) — sem
            // quebra input/output nesse canal; o total inteiro entra em tokensOutput (convenção
            // documentada, não é medição de output real) e offBook=true tira da conta do handoff.
            emissions.add(new CostEmission(PROVIDER, model, 0, parser.subagentTokens(), true));
        }
        return emissions;
    }
}
