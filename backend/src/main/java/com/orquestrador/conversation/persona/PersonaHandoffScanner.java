package com.orquestrador.conversation.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.org.agent.CeoPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai as VIRADAS DE PERSONA da resposta de um CEO-persona (ADR-0031, M5) — o irmão do
 * {@code ProposalScanner}: blocos cercados de JSON, parse DETERMINÍSTICO (zero LLM no caminho),
 * malformado/persona-desconhecida DESCARTADO fail-soft (o texto segue legível na thread).
 *
 * <pre>```handoff
 * {"para": "planejador", "bastao": ".orq/conversa/&lt;conv&gt;/dossie.md", "resumo": "gerar estratégia"}
 * ```</pre>
 * <pre>```needs-grilling
 * {"lacuna": "a pergunta concreta que só o dono responde"}
 * ```</pre>
 *
 * <p>Diferença deliberada do irmão: as cercas só valem EM COLUNA 0 — o exemplo do formato viaja
 * INDENTADO dentro do prompt (M7), então um motor que o ecoa/cita nunca dispara a virada.
 * O bloco {@code ```proposta} NÃO passa por aqui (segue no {@code ProposalScanner} → gate T3).
 */
@Component
public class PersonaHandoffScanner {

    private static final Pattern HANDOFF_BLOCK =
            Pattern.compile("(?m)^```handoff[ \\t]*\\n(.*?)^```", Pattern.DOTALL);
    private static final Pattern NEEDS_GRILLING_BLOCK =
            Pattern.compile("(?m)^```needs-grilling[ \\t]*\\n(.*?)^```", Pattern.DOTALL);
    private static final Pattern CORRECAO_BLOCK =
            Pattern.compile("(?m)^```correcao[ \\t]*\\n(.*?)^```", Pattern.DOTALL);
    private static final Pattern ABSORCAO_BLOCK =
            Pattern.compile("(?m)^```absorcao[ \\t]*\\n(.*?)^```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<Route> scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        List<PositionedRoute> found = new ArrayList<>();
        Matcher handoffs = HANDOFF_BLOCK.matcher(reply);
        while (handoffs.find()) {
            Handoff handoff = parseHandoff(readObject(handoffs.group(1)));
            if (handoff != null) {
                found.add(new PositionedRoute(handoffs.start(), handoff));
            }
        }
        Matcher kickbacks = NEEDS_GRILLING_BLOCK.matcher(reply);
        while (kickbacks.find()) {
            NeedsGrilling kickback = parseNeedsGrilling(readObject(kickbacks.group(1)));
            if (kickback != null) {
                found.add(new PositionedRoute(kickbacks.start(), kickback));
            }
        }
        Matcher correcoes = CORRECAO_BLOCK.matcher(reply);
        while (correcoes.find()) {
            Correcao correcao = parseCorrecao(readObject(correcoes.group(1)));
            if (correcao != null) {
                found.add(new PositionedRoute(correcoes.start(), correcao));
            }
        }
        Matcher absorcoes = ABSORCAO_BLOCK.matcher(reply);
        while (absorcoes.find()) {
            Absorcao absorcao = parseAbsorcao(readObject(absorcoes.group(1)));
            if (absorcao != null) {
                found.add(new PositionedRoute(absorcoes.start(), absorcao));
            }
        }
        return found.stream()
                .sorted((a, b) -> Integer.compare(a.position(), b.position()))
                .map(PositionedRoute::route)
                .toList();
    }

    /**
     * F-N3 (ADR-0039, D2): a entrada ESTRUTURADA — os {@code blocos[]} já validados pelo motor na
     * fonte (schema nativo) chegam parseados; o campo {@code tipo} discrimina. Espelho do regex
     * {@link #scan}, na ORDEM do array (o motor emite na ordem que decidiu), com a MESMA validação
     * de domínio dos parsers (persona conhecida, #N &gt;= 1, campos presentes). O bloco
     * {@code proposta} não sai daqui (segue no {@code ProposalScanner} → gate T3, como no regex).
     */
    public List<Route> scanStructured(List<JsonNode> blocos) {
        if (blocos == null || blocos.isEmpty()) {
            return List.of();
        }
        List<Route> routes = new ArrayList<>();
        for (JsonNode bloco : blocos) {
            Route route = switch (textOrNull(bloco, "tipo") == null ? "" : bloco.path("tipo").asText()) {
                case "handoff" -> parseHandoff(bloco);
                case "needs-grilling" -> parseNeedsGrilling(bloco);
                case "correcao" -> parseCorrecao(bloco);
                case "absorcao" -> parseAbsorcao(bloco);
                default -> null; // proposta (ProposalScanner) ou tipo desconhecido: fail-soft
            };
            if (route != null) {
                routes.add(route);
            }
        }
        return routes;
    }

    private static Handoff parseHandoff(JsonNode node) {
        if (node == null) {
            return null;
        }
        CeoPersona para = CeoPersona.fromDbValue(textOrNull(node, "para")).orElse(null);
        String bastao = textOrNull(node, "bastao");
        if (para == null || bastao == null) {
            return null; // sem alvo válido ou sem entregável não há virada (D6: o bastão É o sinal)
        }
        String resumo = textOrNull(node, "resumo");
        return new Handoff(para, bastao, resumo != null ? resumo : "");
    }

    private static NeedsGrilling parseNeedsGrilling(JsonNode node) {
        if (node == null) {
            return null;
        }
        String lacuna = textOrNull(node, "lacuna");
        return lacuna == null ? null : new NeedsGrilling(lacuna);
    }

    private static Correcao parseCorrecao(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode issue = node.path("issue");
        if (!issue.isIntegralNumber() || !issue.canConvertToInt() || issue.asInt() < 1) {
            return null; // #N e sempre um inteiro >= 1 (ADR-0033); o resto e ruido do motor
        }
        return new Correcao(issue.asInt());
    }

    private static Absorcao parseAbsorcao(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode issue = node.path("issue");
        if (!issue.isIntegralNumber() || !issue.canConvertToInt() || issue.asInt() < 1) {
            return null; // #N e sempre um inteiro >= 1 (ADR-0033); o resto e ruido do motor
        }
        String dominio = textOrNull(node, "dominio");
        String foco = textOrNull(node, "foco");
        String racional = textOrNull(node, "racional");
        if (dominio == null || foco == null || racional == null) {
            // Sem o TIME completo nao ha corrente a resolver; sem racional nao ha evidencia de
            // Decision nem confirmacao legivel (G1/G6) — contrato inteiro ou nada (fail-soft).
            return null;
        }
        return new Absorcao(issue.asInt(), dominio, foco, racional);
    }

    private static JsonNode readObject(String block) {
        try {
            JsonNode node = MAPPER.readTree(block.strip());
            return node.isObject() ? node : null;
        } catch (Exception malformado) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private record PositionedRoute(int position, Route route) {
    }

    /** Uma virada extraída da resposta, na ordem do texto. */
    public sealed interface Route permits Handoff, NeedsGrilling, Correcao, Absorcao {
    }

    /** Bastão adiante (D6): a persona {@code para} reivindica o próximo turno com o entregável. */
    public record Handoff(CeoPersona para, String bastao, String resumo) implements Route {
    }

    /** Kickback cirúrgico (D7): lacuna grande demais para chutar — o Questionador re-grelha o dono. */
    public record NeedsGrilling(String lacuna) implements Route {
    }

    /**
     * Caso B (ADR-0034/D1): o dono apontou que a task ENTREGUE #{@code issue} ficou ruim.
     * Payload mínimo DELIBERADO — só o número: as palavras do dono não viajam no bloco (LLM
     * redigitando = paráfrase); o verbatim é cópia mecânica do turno no banco (D3).
     */
    public record Correcao(int issue) implements Route {
    }

    /**
     * Absorção (ADR-0037/D5): o Planejador triou a issue órfã #{@code issue} do board e aponta o
     * TIME por {@code dominio}/{@code foco} SEPARADOS (texto livre pode conter "/" — nunca parse
     * de string única), NUNCA uma pessoa: o sistema resolve time→arquiteto por construção. O
     * {@code racional} é a palavra do Planejador — vira a {@code evidencia} da Decision (D8) e a
     * confirmação visível na thread (G1/G6).
     */
    public record Absorcao(int issue, String dominio, String foco, String racional) implements Route {
    }
}
