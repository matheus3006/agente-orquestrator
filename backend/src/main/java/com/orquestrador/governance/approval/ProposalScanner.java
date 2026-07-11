package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai PROPOSTAS da resposta do CEO (o canal de SAÍDA, ADR-0018, emendada pela ADR-0036):
 * blocos cercados
 * <pre>```proposta
 * {"acao": "HIRE_AGENT", "alvo": {...}, "resumo": "...", "area": "..."}
 * ```</pre>
 * Parse DETERMINÍSTICO (zero LLM do lado do core), com duas regras da ADR-0036:
 * <ul>
 *   <li><b>Cerca só em COLUNA 0</b> (simetria com o {@code PersonaHandoffScanner}): o exemplo do
 *       formato viaja INDENTADO no prompt do Executor — um motor que o ecoa/cita não cria
 *       Approval fantasma. Espaço = citação, margem = emissão.</li>
 *   <li><b>Descarte CONTADO</b>: bloco malformado ou ação fora do enum segue fail-soft
 *       (deny-by-default — não vira Approval), mas deixa de ser silencioso: WARN no log e a
 *       contagem volta ao settle, que avisa o dono na thread (o gate vazio da Prova R3-A).</li>
 * </ul>
 * O {@code alvo} é re-serializado em forma CANÔNICA (chaves ordenadas) — é sobre ela que o
 * fingerprint de dedupe é calculado.
 */
@Component
public class ProposalScanner {

    private static final Logger log = LoggerFactory.getLogger(ProposalScanner.class);

    private static final Pattern PROPOSTA_BLOCK =
            Pattern.compile("(?m)^```proposta[ \\t]*\\n(.*?)^```", Pattern.DOTALL);

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public Scan scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Scan(List.of(), 0);
        }
        List<Proposal> proposals = new ArrayList<>();
        int descartadas = 0;
        Matcher matcher = PROPOSTA_BLOCK.matcher(reply);
        while (matcher.find()) {
            Proposal proposal = parseBlock(matcher.group(1));
            if (proposal != null) {
                proposals.add(proposal);
            } else {
                descartadas++;
            }
        }
        return new Scan(List.copyOf(proposals), descartadas);
    }

    /** Bloco → Proposal; qualquer desvio (não-JSON, ação desconhecida) → null, contado no Scan. */
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
            log.warn("proposta: bloco em coluna 0 descartado — fora do contrato ({})",
                    malformado.getMessage());
            return null;
        }
    }

    /** A colheita de um reply: as propostas válidas + quantos blocos-cerca foram descartados. */
    public record Scan(List<Proposal> propostas, int descartadas) {
    }

    /** Uma proposta extraída: ação tipada + alvo canônico + a linha legível do card. */
    public record Proposal(ApprovalActionType actionType, String targetDetailJson, String resumo, String area) {
    }
}
