package com.orquestrador.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacao de segredos antes de persistir uma Skill (LGPD). DEFESA-EM-PROFUNDIDADE, NAO garantia:
 * mascara formas reconheciveis (atribuicoes com chave sensivel — inclusive valor em bloco multilinha —,
 * blocos PEM, JWT, credenciais embutidas em URL e prefixos de credencial sk-/AKIA/gh*_). Erra para o
 * lado conservador (prefere mascarar a mais). Segredos de alta entropia SEM padrao reconhecido (ex.:
 * base64 arbitrario) NAO sao garantidamente removidos — a fonte nao deve conter segredos no SKILL.md.
 */
public final class SecretRedactor {

    public static final String MASK = "***REDACTED***";

    // Atribuicao "chave: valor" / "chave = valor" cuja chave (token unico) cita um termo sensivel.
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)^(\\s*[\"']?[\\w.\\-]*"
                    + "(?:api[_\\-]?key|secret|token|password|passwd|access[_\\-]?key|private[_\\-]?key|client[_\\-]?secret|auth)"
                    + "[\\w.\\-]*[\"']?\\s*[:=])(\\s*)(.*)$");

    // Bloco PEM (chave privada) — multilinha.
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{10,}");
    private static final Pattern URL_CRED = Pattern.compile("([a-zA-Z][\\w+.\\-]*://)[^/\\s:@]+:[^/\\s:@]+@");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}");
    private static final Pattern OPENAI = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");
    private static final Pattern AWS = Pattern.compile("AKIA[0-9A-Z]{16}");
    private static final Pattern GITHUB = Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}");

    // Indicador de block scalar YAML (| , > e variantes) — valor real vem nas linhas indentadas abaixo.
    private static final Pattern BLOCK_SCALAR = Pattern.compile("[|>][+-]?\\d*");

    private SecretRedactor() {
    }

    public static String redact(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // Normaliza o fim de linha ANTES de tudo (espelha SkillFrontmatter.parse): um '\r' residual
        // faria a atribuicao sensivel (SENSITIVE_KEY, ancorada em $) NAO casar e o segredo VAZAR.
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String out = PEM.matcher(normalized).replaceAll(MASK);
        out = redactAssignments(out);
        out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
        out = URL_CRED.matcher(out).replaceAll("$1" + MASK + "@");
        out = JWT.matcher(out).replaceAll(MASK);
        out = OPENAI.matcher(out).replaceAll(MASK);
        out = AWS.matcher(out).replaceAll(MASK);
        out = GITHUB.matcher(out).replaceAll(MASK);
        return out;
    }

    /** Mascara o valor de atribuicoes sensiveis, inclusive quando o valor esta em bloco multilinha. */
    private static String redactAssignments(String text) {
        LineCursor cursor = new LineCursor(text);
        List<String> redacted = new ArrayList<>();
        while (cursor.hasLine()) {
            String line = cursor.take();
            Matcher assignment = SENSITIVE_KEY.matcher(line);
            if (!assignment.matches()) {
                redacted.add(line);
                continue;
            }
            String keyPart = assignment.group(1);
            redacted.add(keyPart + " " + MASK);

            String assignedValue = assignment.group(3).trim();
            boolean valueIsInBlock = assignedValue.isEmpty() || BLOCK_SCALAR.matcher(assignedValue).matches();
            if (valueIsInBlock) {
                // O valor real vem nas linhas indentadas abaixo: pertencem ao bloco enquanto forem
                // vazias ou mais indentadas que a chave. Cada linha nao-vazia do bloco vira MASK.
                int keyIndent = leadingWhitespace(keyPart);
                for (String blockLine : cursor.takeBlockWhile(
                        candidate -> candidate.isBlank() || leadingWhitespace(candidate) > keyIndent)) {
                    redacted.add(blockLine.isBlank() ? blockLine : " ".repeat(leadingWhitespace(blockLine)) + MASK);
                }
            }
        }
        return String.join("\n", redacted);
    }

    private static int leadingWhitespace(String line) {
        int column = 0;
        while (column < line.length() && Character.isWhitespace(line.charAt(column))) {
            column++;
        }
        return column;
    }
}
