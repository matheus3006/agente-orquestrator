package com.orquestrador.execution.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser do {@code --output-format stream-json} do `claude` (ADR-0014). Módulo profundo atrás de um seam
 * mínimo (regra de ouro ADR-0010): consome o stdout LINHA-A-LINHA (como o ExecutionTarget entrega) e, a
 * cada linha, devolve um {@link Step} semântico enquanto acumula o {@code session_id}, o {@code model} e o
 * {@code usage} (tokens). Uma linha que não é JSON degrada para um step {@code STDOUT} (fail-soft) — o CLI
 * pode intercalar avisos em texto puro. Testável isolado: o CLI real não roda no teste.
 */
final class ClaudeStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String session;
    private String model;
    private int tokensInput;
    private int tokensOutput;
    private boolean sawUsage;
    private int subagentTokens;
    private String credentialFailureLine;
    private String sessionLostLine;

    /** Um passo do StepTrace derivado de uma linha do stream: {@code type} = evento nativo (UPPER). */
    record Step(String type, String detail) {
    }

    /** Consome uma linha do stdout; acumula o estado e devolve o step a emitir. */
    Step accept(String line) {
        JsonNode node = tryParse(line);
        if (node == null || !node.isObject()) {
            // Só linhas FORA da gramática farejam credencial (ADR-0016, peça 4) e sessão perdida
            // (F-N2/ADR-0038): é onde o CLI avisa auth/resume quebrado; a prosa do modelo vive DENTRO
            // de JSON — sem falso-positivo.
            if (credentialFailureLine == null && CredentialFailureSignal.detectedIn(line)) {
                credentialFailureLine = line;
            }
            if (sessionLostLine == null && SessionLostSignal.detectedIn(line)) {
                sessionLostLine = line;
            }
            return new Step("STDOUT", line == null ? "" : line); // não-JSON → fail-soft
        }
        captureSession(node);
        captureModel(node);
        captureUsage(node);
        captureSubagentUsage(node);
        String type = text(node, "type");
        String tag = (type == null) ? "EVENT" : type.toUpperCase(Locale.ROOT);
        return new Step(tag, describe(node, type));
    }

    String session() {
        return session;
    }

    String model() {
        return model;
    }

    int tokensInput() {
        return tokensInput;
    }

    int tokensOutput() {
        return tokensOutput;
    }

    boolean sawUsage() {
        return sawUsage;
    }

    /**
     * Soma do gasto de TODOS os subagentes deste run (F-N5/ADR-0041, D5): o {@code usage} do turno
     * principal NÃO os inclui (laudo, Sonda 1) — cada subagente, ao completar, emite
     * {@code task_notification.usage.total_tokens} no stream padrão (provado sem flag nova). Soma
     * corrida (não last-wins, ao contrário de {@link #tokensInput()}): cada subagente notifica uma
     * vez só, então acumular é a conta certa de N subagentes.
     */
    int subagentTokens() {
        return subagentTokens;
    }

    /** A primeira linha do stream com cara de falha de credencial; nula se nenhuma. */
    String credentialFailure() {
        return credentialFailureLine;
    }

    /** O stream avisou que a sessão nativa a resumir sumiu (F-N2/ADR-0038)? */
    boolean sessionLost() {
        return sessionLostLine != null;
    }

    private static JsonNode tryParse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(line);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }

    private void captureSession(JsonNode node) {
        String value = text(node, "session_id");
        if (value != null) {
            session = value;
        }
    }

    private void captureModel(JsonNode node) {
        String value = text(node, "model");
        if (value == null) {
            value = text(node.path("message"), "model");
        }
        if (value != null) {
            model = value;
        }
    }

    /**
     * Tokens do bloco {@code usage} — top-level (linha {@code result}, autoritativa) ou {@code message.usage}
     * (linha {@code assistant}). Last-wins: como o {@code result} é a última linha, o total final vence. O
     * input soma cache (read+creation): é o contexto REAL consumido — o denominador do handoff a 50-60%
     * (ADR-0015). Campos ausentes contam 0 (fail-soft).
     */
    private void captureUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode()) {
            usage = node.path("message").path("usage");
        }
        if (usage.isObject()) {
            tokensInput = intAt(usage, "input_tokens")
                    + intAt(usage, "cache_read_input_tokens")
                    + intAt(usage, "cache_creation_input_tokens");
            tokensOutput = intAt(usage, "output_tokens");
            sawUsage = true;
        }
    }

    /** {@code task_notification} = um subagente completou (background task); soma seu total ao off-book. */
    private void captureSubagentUsage(JsonNode node) {
        if (!"task_notification".equals(text(node, "subtype"))) {
            return;
        }
        JsonNode usage = node.path("usage");
        if (usage.isObject()) {
            subagentTokens += intAt(usage, "total_tokens");
        }
    }

    private static String describe(JsonNode node, String type) {
        if (type == null) {
            return firstNonBlank(text(node, "subtype"), "");
        }
        return switch (type) {
            case "assistant" -> describeAssistant(node);
            case "result" -> firstNonBlank(text(node, "result"), text(node, "subtype"), "result");
            case "system" -> firstNonBlank(text(node, "subtype"), "system");
            case "user" -> "tool_result";
            default -> firstNonBlank(text(node, "subtype"), "");
        };
    }

    /** Detalhe de um {@code assistant}: a prosa dos blocos de texto e/ou os tool_use ({@code → nome}). */
    private static String describeAssistant(JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray() || content.isEmpty()) {
            return "assistant";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            String blockType = block.path("type").asText("");
            if (blockType.equals("text")) {
                String prose = block.path("text").asText("").strip();
                if (!prose.isEmpty()) {
                    parts.add(prose);
                }
            } else if (blockType.equals("tool_use")) {
                parts.add("→ " + block.path("name").asText("tool"));
            }
        }
        return parts.isEmpty() ? "assistant" : String.join(" ", parts);
    }

    private static int intAt(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isNumber() ? value.asInt() : 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return (value.isMissingNode() || value.isNull()) ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
