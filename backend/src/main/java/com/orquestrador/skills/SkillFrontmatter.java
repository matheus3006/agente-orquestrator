package com.orquestrador.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Frontmatter minimo de um SKILL.md: bloco entre cercas {@code ---} no topo, com pares
 * {@code chave: valor}. Parser proprio (sem dep de YAML) — robusto a bumps do Spring e suficiente
 * para os campos que usamos: {@code name} (vira o skill_key), {@code description}, {@code triggers}.
 * Cobre escalar em linha, listas YAML ({@code - item}, juntadas por ", ") e valores multilinha /
 * block scalar ({@code |}, {@code >}, juntados por espaco). Tolera CRLF.
 */
public record SkillFrontmatter(String name, String description, String triggers) {

    private static final String FENCE = "---";
    private static final SkillFrontmatter EMPTY = new SkillFrontmatter(null, null, null);
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>][+-]?\\d*");

    public static SkillFrontmatter parse(String content) {
        if (content == null) {
            return EMPTY;
        }
        String text = content.replace("\r\n", "\n").stripLeading();
        if (!text.startsWith(FENCE)) {
            return EMPTY;
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return EMPTY;
        }
        int closing = text.indexOf("\n---", firstBreak);
        if (closing < 0) {
            return EMPTY;
        }
        LineCursor cursor = new LineCursor(text.substring(firstBreak + 1, closing));

        String name = null;
        String description = null;
        String triggers = null;

        while (cursor.hasLine()) {
            String rawLine = cursor.take();
            if (rawLine.isBlank() || Character.isWhitespace(rawLine.charAt(0))) {
                continue; // linha em branco ou continuacao (consumida via look-ahead)
            }
            int colon = rawLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = rawLine.substring(0, colon).trim().toLowerCase();
            String value = stripQuotes(rawLine.substring(colon + 1).trim());

            if (value.isEmpty() || BLOCK_SCALAR.matcher(value).matches()) {
                // Valor multilinha / block scalar: as linhas indentadas abaixo sao a continuacao.
                value = joinContinuation(cursor.takeBlockWhile(SkillFrontmatter::isContinuationLine));
            }

            switch (key) {
                case "name" -> name = emptyToNull(value);
                case "description" -> description = emptyToNull(value);
                case "triggers", "trigger" -> triggers = emptyToNull(value);
                default -> {
                    // ignora outras chaves
                }
            }
        }
        return new SkillFrontmatter(name, description, triggers);
    }

    /** Linha de continuacao de um valor: nao-vazia e comecando com indentacao. */
    private static boolean isContinuationLine(String line) {
        return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    /**
     * Junta as linhas de continuacao num unico valor. Itens de lista YAML ({@code - item}) juntam-se
     * por ", "; escalares em bloco ({@code |}, {@code >}) juntam-se por espaco. Linhas so-com-espacos
     * sao ignoradas. Havendo mistura de lista e texto plano, o espaco prevalece.
     */
    private static String joinContinuation(List<String> block) {
        List<String> gathered = new ArrayList<>();
        boolean anyList = false;
        boolean anyPlain = false;
        for (String line : block) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                gathered.add(stripQuotes(trimmed.substring(2).trim()));
                anyList = true;
            } else {
                gathered.add(stripQuotes(trimmed));
                anyPlain = true;
            }
        }
        String joiner = anyList && !anyPlain ? ", " : " ";
        return String.join(joiner, gathered);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
