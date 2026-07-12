package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Bring your own agent": um unico {@code execute()} absorve toda diferenca de runtime (ADR-0004).
 * Cada runtime novo (Claude Code, Codex, …) e uma nova impl — zero mudanca no core.
 * O {@code StepSink} recebe os passos (StepTrace); o handle de sessao entra/sai pelo Context/Result.
 */
public interface AgentAdapter {

    String type();

    Result execute(Context context, StepSink steps);

    /**
     * O material de container que ESTE adapter declara (F.8/ADR-0016). Quem pergunta sao o
     * capturador de preview (leitor, F.9/ADR-0021) e o executor (escritor de workspace) — ambos
     * usam SO a imagem, como valor opaco do Spec hermetico; as credenciais continuam assunto
     * exclusivo do adapter (nunca entram em leitor nem escritor).
     */
    EngineContainer engineContainer();

    /**
     * A convenção de workspace que ESTE adapter declara (ADR-0032, irmã do {@link #engineContainer()}):
     * onde o CLI lê carta e skills. Default = layout do Claude Code ({@code .claude/}) — o
     * ProcessAgentAdapter herda por arquétipo (o layout demo estabelecido); só o CodexAdapter
     * sobrescreve. Consumidores: o executor (subtrees + faxina) e os 2 materializers (caminho
     * por parâmetro) — o adapter declara DADOS, nunca faz I/O.
     */
    default WorkspaceConvention workspaceConvention() {
        return WorkspaceConvention.claude();
    }

    /**
     * Como este runtime recebe um PROMPT por-Run (turno de conversa, ADR-0018): {@code true} =
     * o prompt substitui o {@code command} do Context (adapters ricos: vira o {@code -p} do CLI);
     * {@code false} = o {@code command} continua sendo o comando de shell e o prompt chega SÓ pelo
     * env {@code ORQ_PROMPT} (arquétipo process — prosa em {@code sh -c} seria executada como shell).
     */
    default boolean acceptsPromptAsCommand() {
        return true;
    }

    interface StepSink {
        void emit(String type, String detail);
    }

    /**
     * A imagem NÃO entra aqui (F.8, ADR-0016): o container de um runtime — imagem pinada +
     * credencial do plano — é do adapter ({@link EngineContainer}); o executor não o conhece.
     *
     * <p>{@code outputSchema} (F-N3/ADR-0039, D2): o JSON Schema que PRENDE a saída do turno a
     * {@code {resposta, blocos[]}} — o adapter rico o entrega ao seu CLI (claude {@code --json-schema}
     * inline · codex grava um arquivo e passa {@code --output-schema}). Nulo ⟹ turno não migrado
     * (persona legada / etapa não ligada): saída em prosa, o caminho pré-F-N3.
     */
    record Context(UUID companyId, UUID agentId, UUID runId, UUID issueId, Path workDir,
                   String command, Map<String, String> env, ExecutionTarget target,
                   String volumeName, String priorSession,
                   String model, String effort, String outputSchema) {

        /** Sem schema de saída: o caminho não-migrado e as redes (resume-cirúrgico, fresh start). */
        public Context(UUID companyId, UUID agentId, UUID runId, UUID issueId, Path workDir,
                       String command, Map<String, String> env, ExecutionTarget target,
                       String volumeName, String priorSession, String model, String effort) {
            this(companyId, agentId, runId, issueId, workDir, command, env, target, volumeName,
                    priorSession, model, effort, null);
        }
    }

    /**
     * Um custo emitido pelo adapter num Run: provider+model+tokens REAIS (ADR-0014, Q7). O
     * {@code costCents} NÃO vive aqui: no modelo de assinatura ele é sempre 0, então o executor
     * roteia cada emissão para {@code BudgetService.recordCost(..., costCents=0, ...)} — o hard-stop
     * de dólares (ADR-0006) fica inócuo, mas os tokens instrumentam o handoff a 50-60% (ADR-0015).
     */
    record CostEmission(String provider, String model, int tokensInput, int tokensOutput) {
    }

    /**
     * {@code costEvents} = tokens instrumentados pelos adapters ricos (Claude/Codex); vazio no
     * arquétipo process. Emitidos no sucesso E na falha (os tokens gastos antes de falhar contam).
     * {@code sessionLost} (F-N2/ADR-0038): a falha foi "a sessão nativa sumiu" num run que resumiu
     * (priorSession) — o executor reage com {@code RUN_SESSION_LOST} + fresh start, em vez de só
     * marcar FAILED. O construtor compacto blinda: nunca nulo, sempre imutável.
     */
    record Result(boolean succeeded, String error, String session, List<CostEmission> costEvents,
                  boolean sessionLost) {
        public Result {
            costEvents = (costEvents == null) ? List.of() : List.copyOf(costEvents);
        }

        public static Result ok(String session) {
            return new Result(true, null, session, List.of(), false);
        }

        public static Result ok(String session, List<CostEmission> costEvents) {
            return new Result(true, null, session, costEvents, false);
        }

        public static Result fail(String error) {
            return new Result(false, error, null, List.of(), false);
        }

        public static Result fail(String error, List<CostEmission> costEvents) {
            return new Result(false, error, null, costEvents, false);
        }

        /** Falha por sessão nativa perdida (resume apontou p/ handle sumido): dispara o fresh start. */
        public static Result failSessionLost(String error, List<CostEmission> costEvents) {
            return new Result(false, error, null, costEvents, true);
        }
    }
}
