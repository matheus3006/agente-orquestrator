package com.orquestrador.governance.approval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai ORDENS DE DIRIGIR da saída do run de um GESTOR (ADR-0047, D-DSG-1): blocos cercados
 * <pre>```dirigir
 * {"alvo": "&lt;nome do agente&gt;", "titulo": "...", "descricao": "...", "risco": 1, "racional": "..."}
 * ```</pre>
 * Irmão do {@link SkillRequestScanner} (molde F-AT1): parse DETERMINÍSTICO (zero LLM) sobre o
 * texto cru, com as mesmas duas regras da ADR-0036 — cerca só em COLUNA 0 (exemplo indentado no
 * prompt não vira ordem fantasma) e descarte CONTADO (fail-soft, nunca silencioso). O RACIONAL é
 * OBRIGATÓRIO (D-DSG-4): bloco sem o porquê é descartado com aviso — é a mesma régua que o gate
 * aplica ao próprio dono no decide. {@code risco} ausente vale 1; fora da escala 1-5 (ADR-0012)
 * é descarte, não clamp. Quem resolve o NOME do alvo e checa o braço é o {@link DirigirGate}.
 */
@Component
public class DirigirScanner {

    private static final Logger log = LoggerFactory.getLogger(DirigirScanner.class);

    private static final Pattern DIRIGIR_BLOCK =
            Pattern.compile("(?m)^```dirigir[ \\t]*\\n(.*?)^```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Scan scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Scan(List.of(), 0);
        }
        List<Ordem> ordens = new ArrayList<>();
        int descartadas = 0;
        Matcher matcher = DIRIGIR_BLOCK.matcher(reply);
        while (matcher.find()) {
            Ordem ordem = parseBlock(matcher.group(1));
            if (ordem != null) {
                ordens.add(ordem);
            } else {
                descartadas++;
            }
        }
        return new Scan(List.copyOf(ordens), descartadas);
    }

    /** Bloco cercado → Ordem; não-JSON, sem alvo/titulo, SEM RACIONAL ou risco inválido → null (contado). */
    private static Ordem parseBlock(String block) {
        JsonNode node;
        try {
            node = MAPPER.readTree(block.strip());
        } catch (Exception naoJson) {
            log.warn("dirigir: bloco em coluna 0 descartado — não é JSON ({})", naoJson.getMessage());
            return null;
        }
        if (!node.isObject()) {
            log.warn("dirigir: bloco descartado — não é objeto JSON");
            return null;
        }
        String alvo = textOrNull(node, "alvo");
        String titulo = textOrNull(node, "titulo");
        String racional = textOrNull(node, "racional");
        if (alvo == null || titulo == null) {
            log.warn("dirigir: bloco descartado — sem alvo/titulo");
            return null;
        }
        if (racional == null) {
            log.warn("dirigir: bloco descartado — RACIONAL obrigatório (D-DSG-4), o porquê não veio");
            return null;
        }
        JsonNode risco = node.path("risco");
        if (!risco.isMissingNode() && !risco.isNull() && (!risco.isInt() || risco.asInt() < 1 || risco.asInt() > 5)) {
            log.warn("dirigir: bloco descartado — risco fora da escala 1-5 (ADR-0012): {}", risco);
            return null;
        }
        return new Ordem(alvo.strip(), titulo.strip(), textOrNull(node, "descricao"),
                risco.isInt() ? risco.asInt() : 1, racional.strip());
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** A colheita de um reply: as ordens válidas + quantos blocos-cerca foram descartados. */
    public record Scan(List<Ordem> ordens, int descartadas) {
    }

    /** Uma ordem extraída: o alvo por NOME (o guard resolve/checa o braço), risco default 1. */
    public record Ordem(String alvo, String titulo, String descricao, int risco, String racional) {
    }
}
