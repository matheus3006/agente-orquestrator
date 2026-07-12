package com.orquestrador.execution.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * O side-channel do Codex (F-N5/ADR-0041, D5): o stream do {@code exec --json} do parent NÃO traz
 * o {@code usage} dos subagentes (laudo, Sonda 1) — só o {@code thread_id} de cada um (via
 * {@link CodexStreamParser#childThreadIds()}). Cada subagente é um THREAD próprio com session
 * JSONL própria em {@code ~/.codex/sessions/**} (particionado por dia); este leitor localiza o
 * rollout de cada filho pelo nome do arquivo (sufixo {@code -<threadId>.jsonl}) e soma o último
 * {@code token_count} (cumulativo, mesma convenção last-wins do {@link CodexStreamParser}).
 *
 * <p>PROIBIDO por decisão D5: proxy de rede próprio, OTEL/collector. Este é um leitor de ARQUIVO,
 * nada mais. Tolerante: rollout ausente ou ilegível ⟹ WARN + contribui 0 para aquele filho — a
 * contabilidade off-book NUNCA quebra o run que a causou.
 */
final class CodexSubagentUsageReader {

    private static final Logger log = LoggerFactory.getLogger(CodexSubagentUsageReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sessionsRoot;

    CodexSubagentUsageReader(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    /** O gasto REAL somado (mesma convenção de input do CodexStreamParser: input + cached_input). */
    record Usage(int tokensInput, int tokensOutput) {
        static final Usage ZERO = new Usage(0, 0);

        private Usage plus(Usage other) {
            return new Usage(tokensInput + other.tokensInput, tokensOutput + other.tokensOutput);
        }
    }

    Usage read(Set<String> childThreadIds) {
        Usage total = Usage.ZERO;
        for (String threadId : childThreadIds) {
            total = total.plus(readOne(threadId));
        }
        return total;
    }

    private Usage readOne(String threadId) {
        Optional<Path> rollout = locate(threadId);
        if (rollout.isEmpty()) {
            log.warn("F-N5/ADR-0041: rollout do subagente {} nao encontrado em {} — gasto off-book "
                    + "deste filho fica de fora da conta (fail-soft)", threadId, sessionsRoot);
            return Usage.ZERO;
        }
        return readTokenCount(rollout.get());
    }

    private Optional<Path> locate(String threadId) {
        if (!Files.isDirectory(sessionsRoot)) {
            return Optional.empty();
        }
        String suffix = "-" + threadId + ".jsonl";
        try (Stream<Path> walk = Files.walk(sessionsRoot)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(suffix)).findFirst();
        } catch (IOException falhaAoListar) {
            log.warn("F-N5/ADR-0041: falha lendo {} — {}", sessionsRoot, falhaAoListar.getMessage());
            return Optional.empty();
        }
    }

    private static Usage readTokenCount(Path rollout) {
        Usage last = Usage.ZERO;
        try {
            for (String line : Files.readAllLines(rollout)) {
                JsonNode node = tryParse(line);
                Usage found = tokenCountIn(node);
                if (found != null) {
                    last = found; // last-wins: cada token_count já é o total cumulativo do CLI
                }
            }
        } catch (IOException falhaDeLeitura) {
            log.warn("F-N5/ADR-0041: falha lendo rollout {} — {}", rollout, falhaDeLeitura.getMessage());
            return Usage.ZERO;
        }
        return last;
    }

    private static Usage tokenCountIn(JsonNode node) {
        if (node == null || !"event_msg".equals(text(node, "type"))) {
            return null;
        }
        JsonNode payload = node.path("payload");
        if (!"token_count".equals(text(payload, "type"))) {
            return null;
        }
        JsonNode total = payload.path("info").path("total_token_usage");
        if (!total.isObject()) {
            return null;
        }
        return new Usage(intAt(total, "input_tokens") + intAt(total, "cached_input_tokens"),
                intAt(total, "output_tokens"));
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

    private static int intAt(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isNumber() ? value.asInt() : 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return (value.isMissingNode() || value.isNull()) ? null : value.asText();
    }
}
