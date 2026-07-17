package com.orquestrador.governance.approval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
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

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

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

    /**
     * F-N3 (ADR-0039, D2): a entrada ESTRUTURADA — os {@code blocos[]} já validados pelo motor na
     * fonte (schema nativo). Espelho do {@link #scan}: só o bloco {@code tipo=proposta} vira Approval
     * (os demais são de outros roteadores); o resto do contrato do gate (alvo canônico, descarte
     * CONTADO para o anti-silêncio) é IDÊNTICO — só o transporte mudou (JSON validado × regex).
     */
    public Scan scanStructured(List<JsonNode> blocos) {
        if (blocos == null || blocos.isEmpty()) {
            return new Scan(List.of(), 0);
        }
        List<Proposal> proposals = new ArrayList<>();
        int descartadas = 0;
        for (JsonNode bloco : blocos) {
            if (!"proposta".equals(bloco.path("tipo").asText(""))) {
                continue; // só proposta é do gate T3; handoff/absorcao/... seguem noutros roteadores
            }
            Proposal proposal = parseNode(bloco);
            if (proposal != null) {
                proposals.add(proposal);
            } else {
                descartadas++;
            }
        }
        return new Scan(List.copyOf(proposals), descartadas);
    }

    /** Bloco cercado (regex) → Proposal; não-JSON → null, contado no Scan (fail-soft). */
    private static Proposal parseBlock(String block) {
        JsonNode node;
        try {
            node = CANONICAL.readTree(block.strip());
        } catch (Exception naoJson) {
            log.warn("proposta: bloco em coluna 0 descartado — não é JSON ({})", naoJson.getMessage());
            return null;
        }
        return parseNode(node);
    }

    /** Nó JSON → Proposal; qualquer desvio (não-objeto, ação fora do enum) → null, contado no Scan. */
    private static Proposal parseNode(JsonNode node) {
        try {
            if (node == null || !node.isObject()) {
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
            log.warn("proposta: bloco descartado — fora do contrato ({})", malformado.getMessage());
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
