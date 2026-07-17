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
 * Extrai PEDIDOS DE SKILL da saída do EXECUTOR (F-AT1, grill agente-como-tool D6): blocos cercados
 * <pre>```pedido-skill
 * {"skillKey": "..."}
 * ```</pre>
 * Irmão do {@link ProposalScanner} — mesmo desenho (D6 nota-de-lealdade): o Run de Issue não tem
 * saída "em forma de turno" (sem {@code outputSchema}, ver {@code AdapterRunExecutor}), então este
 * scanner é INTERINO sobre o texto cru, não a entrada estruturada da ADR-0039. Parse
 * DETERMINÍSTICO (zero LLM), com as mesmas duas regras da ADR-0036: cerca só em COLUNA 0 (um
 * exemplo indentado no prompt não cria pedido fantasma) e descarte CONTADO (fail-soft,
 * deny-by-default, nunca silencioso).
 */
@Component
public class SkillRequestScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillRequestScanner.class);

    private static final Pattern PEDIDO_SKILL_BLOCK =
            Pattern.compile("(?m)^```pedido-skill[ \\t]*\\n(.*?)^```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Scan scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Scan(List.of(), 0);
        }
        List<SkillRequest> pedidos = new ArrayList<>();
        int descartadas = 0;
        Matcher matcher = PEDIDO_SKILL_BLOCK.matcher(reply);
        while (matcher.find()) {
            SkillRequest pedido = parseBlock(matcher.group(1));
            if (pedido != null) {
                pedidos.add(pedido);
            } else {
                descartadas++;
            }
        }
        return new Scan(List.copyOf(pedidos), descartadas);
    }

    /** Bloco cercado (regex) → SkillRequest; não-JSON ou skillKey ausente/em branco → null (contado). */
    private static SkillRequest parseBlock(String block) {
        JsonNode node;
        try {
            node = MAPPER.readTree(block.strip());
        } catch (Exception naoJson) {
            log.warn("pedido-skill: bloco em coluna 0 descartado — não é JSON ({})", naoJson.getMessage());
            return null;
        }
        JsonNode skillKey = node.path("skillKey");
        if (!node.isObject() || !skillKey.isTextual() || skillKey.asText().isBlank()) {
            log.warn("pedido-skill: bloco descartado — sem skillKey");
            return null;
        }
        return new SkillRequest(skillKey.asText());
    }

    /** A colheita de um reply: os pedidos válidos + quantos blocos-cerca foram descartados. */
    public record Scan(List<SkillRequest> pedidos, int descartadas) {
    }

    /** Um pedido de skill extraído: a chave GLOBAL que o executor bateu na parede sem ter. */
    public record SkillRequest(String skillKey) {
    }
}
