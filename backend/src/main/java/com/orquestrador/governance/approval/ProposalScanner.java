package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai PROPOSTAS da resposta do CEO (o canal de SAÍDA, ADR-0018): blocos cercados
 * <pre>```proposta
 * {"acao": "HIRE_AGENT", "alvo": {...}, "resumo": "...", "area": "..."}
 * ```</pre>
 * Parse DETERMINÍSTICO (zero LLM do lado do core): bloco malformado ou ação fora do enum é
 * DESCARTADO fail-soft (deny-by-default — proposta que o core não entende não vira Approval;
 * o texto segue visível na thread para o dono cobrar). O {@code alvo} é re-serializado em forma
 * CANÔNICA (chaves ordenadas) — é sobre ela que o fingerprint de dedupe é calculado.
 */
@Component
public class ProposalScanner {

    private static final Pattern PROPOSTA_BLOCK =
            Pattern.compile("```proposta\\s*\\n(.*?)```", Pattern.DOTALL);

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public List<Proposal> scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        List<Proposal> proposals = new ArrayList<>();
        Matcher matcher = PROPOSTA_BLOCK.matcher(reply);
        while (matcher.find()) {
            Proposal proposal = parseBlock(matcher.group(1));
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        return proposals;
    }

    /** Bloco → Proposal; qualquer desvio (não-JSON, ação desconhecida) → null (descartado fail-soft). */
    private static Proposal parseBlock(String block) {
        try {
            JsonNode node = CANONICAL.readTree(block.strip());
            if (!node.isObject()) {
                return null;
            }
            ApprovalActionType action = ApprovalActionType.valueOf(
                    node.path("acao").asText("").strip().toUpperCase(Locale.ROOT));
            JsonNode alvo = node.path("alvo");
            JsonNode canonicalAlvo = alvo.isObject() ? alvo : JsonNodeFactory.instance.objectNode();
            String targetDetail = CANONICAL.writeValueAsString(CANONICAL.treeToValue(canonicalAlvo, Object.class));
            String resumo = node.path("resumo").isTextual() ? node.path("resumo").asText() : null;
            String area = node.path("area").isTextual() ? node.path("area").asText() : null;
            return new Proposal(action, targetDetail, resumo, area);
        } catch (Exception malformado) {
            return null;
        }
    }

    /** Uma proposta extraída: ação tipada + alvo canônico + a linha legível do card. */
    public record Proposal(ApprovalActionType actionType, String targetDetailJson, String resumo, String area) {
    }
}
