package com.orquestrador.execution.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

/**
 * Parser do {@code codex exec --json} (ADR-0014). Mesmo contrato do {@link ClaudeStreamParser} (Step + os
 * acumuladores session/model/usage), gramática própria do Codex: eventos JSONL {@code thread.started}
 * (traz {@code thread_id} = a session), {@code turn.started}/{@code turn.completed} (o último traz
 * {@code usage}), {@code item.*} (o conteúdo real — mensagem, reasoning, comando). Linha não-JSON degrada
 * para {@code STDOUT} (fail-soft). Testável isolado (o CLI real não roda no teste — regra de ouro ADR-0010).
 *
 * <p>O shape do {@code usage}/session foi montado do formato documentado, não de fixture do CLI real —
 * verificar no fechamento do backend (risco anotado no handoff D→G).
 */
final class CodexStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Os rótulos de item do codex que carregam a MENSAGEM do agente (a prosa endereçada ao dono). O CLI
     * real (0.142.5) usa {@code agent_message}; {@code assistant_message} aparece na documentação/fixtures
     * — os dois valem. NÃO inclui {@code reasoning} (que também tem {@code text}) nem {@code command_execution}.
     */
    private static final Set<String> AGENT_MESSAGE_ITEM_TYPES = Set.of("agent_message", "assistant_message");

    private String session;
    private String model;
    private int tokensInput;
    private int tokensOutput;
    private boolean sawUsage;
    private String credentialFailureLine;

    /** Um passo do StepTrace derivado de um evento do stream: {@code type} = evento nativo (UPPER). */
    record Step(String type, String detail) {
    }

    Step accept(String line) {
        JsonNode node = tryParse(line);
        if (node == null || !node.isObject()) {
            // Texto puro fareja credencial (ADR-0016, peça 4) — a prosa do modelo vive em JSON.
            if (credentialFailureLine == null && CredentialFailureSignal.detectedIn(line)) {
                credentialFailureLine = line;
            }
            return new Step("STDOUT", line == null ? "" : line); // não-JSON → fail-soft
        }
        captureSession(node);
        captureModel(node);
        captureUsage(node);
        String type = text(node, "type");
        // No Codex a auth quebrada também chega DENTRO da gramática: evento error com a message.
        if ("error".equals(type)) {
            String message = text(node, "message");
            if (credentialFailureLine == null && CredentialFailureSignal.detectedIn(message)) {
                credentialFailureLine = message;
            }
        }
        return new Step(stepTag(node, type), describe(node, type));
    }

    /**
     * O nível do Step no vocabulário compartilhado do {@code RunFinalOutput} (ADR-0010:
     * {@code RESULT > ASSISTANT > STDOUT}). Quase sempre é o evento nativo em UPPER; a exceção é a
     * MENSAGEM do agente ({@code agent_message}), que sobe ao nível {@code RESULT} — last-wins, então o
     * reply do turno é a ÚLTIMA mensagem (a resposta ao dono), VENCENDO o {@code STDOUT} cru (o aviso
     * "Reading additional input from stdin..."). Difere do claude de propósito: os {@code ASSISTANT} do
     * claude são CHUNKS de uma resposta (juntam); cada {@code agent_message} do codex é uma mensagem
     * COMPLETA e a última é a resposta — daí {@code RESULT} (last-wins), não {@code ASSISTANT} (junta), que
     * despejaria a narração de progresso no reply. Reasoning e comando seguem {@code ITEM.COMPLETED}, fora do reply.
     */
    private static String stepTag(JsonNode node, String type) {
        if (type == null) {
            return "EVENT";
        }
        if (isAgentMessage(type, node.path("item"))) {
            return "RESULT";
        }
        return type.toUpperCase(Locale.ROOT);
    }

    /** A mensagem do agente: um {@code item.completed} cujo item é uma mensagem do agente COM texto. */
    private static boolean isAgentMessage(String type, JsonNode item) {
        if (!"item.completed".equals(type)) {
            return false;
        }
        String itemType = firstNonBlank(text(item, "type"), text(item, "item_type"));
        return AGENT_MESSAGE_ITEM_TYPES.contains(itemType) && !firstNonBlank(text(item, "text")).isBlank();
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

    /** A primeira linha/mensagem do stream com cara de falha de credencial; nula se nenhuma. */
    String credentialFailure() {
        return credentialFailureLine;
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

    /** Session do Codex = {@code thread_id} (evento {@code thread.started}). */
    private void captureSession(JsonNode node) {
        String value = text(node, "thread_id");
        if (value != null) {
            session = value;
        }
    }

    private void captureModel(JsonNode node) {
        String value = text(node, "model");
        if (value != null) {
            model = value;
        }
    }

    /**
     * Tokens do {@code usage} (top-level no {@code turn.completed}). Input soma o cache
     * ({@code cached_input_tokens}) = contexto REAL consumido (denominador do handoff 50-60%, ADR-0015).
     * Campos ausentes contam 0 (fail-soft).
     */
    private void captureUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isObject()) {
            tokensInput = intAt(usage, "input_tokens") + intAt(usage, "cached_input_tokens");
            tokensOutput = intAt(usage, "output_tokens");
            sawUsage = true;
        }
    }

    private static String describe(JsonNode node, String type) {
        if (type == null) {
            return "";
        }
        if (type.startsWith("item")) {
            return describeItem(node.path("item"));
        }
        return switch (type) {
            case "thread.started" -> "thread started";
            case "turn.started" -> "turn started";
            case "turn.completed" -> "turn completed";
            case "error" -> firstNonBlank(text(node, "message"), "error");
            default -> firstNonBlank(text(node, "subtype"), "");
        };
    }

    /** Detalhe de um {@code item.*}: o texto/comando se houver, senão o tipo do item. */
    private static String describeItem(JsonNode item) {
        String content = firstNonBlank(text(item, "text"), text(item, "command"));
        if (!content.isBlank()) {
            return content;
        }
        return firstNonBlank(text(item, "item_type"), text(item, "type"), "item");
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
