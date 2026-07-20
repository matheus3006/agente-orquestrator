package com.orquestrador.governance.parecer;

import com.orquestrador.work.review.ReviewDecision;
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
 * Extrai PARECERES DE REVIEW da saída do run de um GESTOR (ADR-0048, F-ORG-C): blocos cercados
 * <pre>```parecer
 * {"issue": 42, "decisao": "APROVAR", "parecer": "por que eu decidiria assim"}
 * ```</pre>
 * Irmão do {@link com.orquestrador.governance.approval.DirigirScanner} (molde F-AT1): parse
 * DETERMINÍSTICO (zero LLM) sobre o texto cru, com as mesmas duas regras da ADR-0036 — cerca só
 * em COLUNA 0 (exemplo indentado no prompt não vira parecer fantasma) e descarte CONTADO
 * (fail-soft, nunca silencioso).
 *
 * <p>A issue é endereçada pelo {@code #N} humano (ADR-0033), não por UUID: mesmo racional do
 * {@code alvo} por NOME no dirigir — o LLM sabe o #N que leu, alucina UUID. Quem resolve o #N,
 * acha o ciclo de review aberto e checa o braço é o {@link ParecerGate}.
 *
 * <p>Os TRÊS campos são obrigatórios, inclusive nos dois ramos: {@code parecer} é o porquê (mesma
 * régua do {@code racional} do dirigir, D-DSG-4 — parecer sem o porquê não é parecer), e
 * {@code decisao} é a POSIÇÃO do gestor. O MESMO bloco serve estágio e promovido — o gestor não
 * precisa saber o próprio status (D-DSG-1): em estágio a posição vira texto ao lado do card do
 * dono; promovido, ela decide o ciclo.
 */
@Component
public class ParecerScanner {

    private static final Logger log = LoggerFactory.getLogger(ParecerScanner.class);

    private static final Pattern PARECER_BLOCK =
            Pattern.compile("(?m)^```parecer[ \\t]*\\n(.*?)^```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Scan scan(String reply) {
        if (reply == null || reply.isBlank()) {
            return new Scan(List.of(), 0);
        }
        List<Parecer> pareceres = new ArrayList<>();
        int descartados = 0;
        Matcher matcher = PARECER_BLOCK.matcher(reply);
        while (matcher.find()) {
            Parecer parecer = parseBlock(matcher.group(1));
            if (parecer != null) {
                pareceres.add(parecer);
            } else {
                descartados++;
            }
        }
        return new Scan(List.copyOf(pareceres), descartados);
    }

    private static Parecer parseBlock(String block) {
        JsonNode node;
        try {
            node = MAPPER.readTree(block.strip());
        } catch (Exception naoJson) {
            log.warn("parecer: bloco em coluna 0 descartado — não é JSON ({})", naoJson.getMessage());
            return null;
        }
        if (!node.isObject()) {
            log.warn("parecer: bloco descartado — não é objeto JSON");
            return null;
        }
        JsonNode issue = node.path("issue");
        if (!issue.isInt() || issue.asInt() < 1) {
            log.warn("parecer: bloco descartado — 'issue' tem que ser o #N humano da issue"
                    + " (inteiro >= 1, ADR-0033): {}", issue);
            return null;
        }
        ReviewDecision decisao = decisaoDe(textOrNull(node, "decisao"));
        if (decisao == null) {
            log.warn("parecer: bloco descartado — 'decisao' tem que ser APROVAR ou REJEITAR");
            return null;
        }
        String texto = textOrNull(node, "parecer");
        if (texto == null) {
            log.warn("parecer: bloco descartado — PARECER obrigatório, o porquê não veio"
                    + " (mesma régua do racional do dirigir, D-DSG-4)");
            return null;
        }
        return new Parecer(issue.asInt(), decisao, texto.strip());
    }

    /** O vocabulário do bloco é o do gestor (pt-BR); o enum da casa é o {@link ReviewDecision}. */
    private static ReviewDecision decisaoDe(String decisao) {
        if (decisao == null) {
            return null;
        }
        return switch (decisao.strip().toUpperCase()) {
            case "APROVAR" -> ReviewDecision.APPROVE;
            case "REJEITAR" -> ReviewDecision.REJECT;
            default -> null;
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** O resultado da varredura: o que passou + quantos blocos caíram (aviso do gate). */
    public record Scan(List<Parecer> pareceres, int descartados) { }

    /** Um parecer já validado — {@code issueSeq} ainda é o #N humano, não resolvido. */
    public record Parecer(int issueSeq, ReviewDecision decisao, String texto) { }
}
