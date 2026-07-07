package com.orquestrador.execution;

import com.orquestrador.execution.adapter.AgentAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * A SAÍDA FINAL de um Run derivada do StepTrace — a regra única (ADR-0010) atrás do reply de
 * turno (thread do CEO) e do report-por-Issue (Ledger, ADR-0019/D3): a linha {@code RESULT} do
 * stream (o texto final do claude) VENCE; sem ela, a prosa {@code ASSISTANT}; por último o
 * {@code STDOUT} cru (arquétipo process/demo). CAPADA — saída final é conversa/report, não
 * despejo; a profundidade fica no StepTrace (1 clique pelo run_id). Alimenta-se ao vivo
 * ({@link #wrap} decora o sink do executor) ou post-hoc ({@link #accept} sobre eventos gravados).
 */
public class RunFinalOutput {

    static final int OUTPUT_CAP_CHARS = 20_000;

    private final List<String> resultLines = new ArrayList<>();
    private final List<String> proseLines = new ArrayList<>();
    private final List<String> stdoutLines = new ArrayList<>();

    /** Decora o sink do executor: observa cada step E repassa intacto ao StepTrace. */
    public AgentAdapter.StepSink wrap(AgentAdapter.StepSink real) {
        return (type, detail) -> {
            accept(type, detail);
            real.emit(type, detail);
        };
    }

    public void accept(String type, String detail) {
        if (type == null || detail == null || detail.isBlank()) {
            return;
        }
        switch (type) {
            case "RESULT" -> resultLines.add(detail);
            case "ASSISTANT" -> proseLines.add(detail);
            case "STDOUT" -> stdoutLines.add(detail);
            default -> {
            }
        }
    }

    public String text() {
        String collected;
        if (!resultLines.isEmpty()) {
            collected = resultLines.get(resultLines.size() - 1); // o result final é a saída
        } else if (!proseLines.isEmpty()) {
            collected = String.join("\n", proseLines);
        } else {
            collected = String.join("\n", stdoutLines);
        }
        return collected.length() <= OUTPUT_CAP_CHARS ? collected : collected.substring(0, OUTPUT_CAP_CHARS);
    }
}
