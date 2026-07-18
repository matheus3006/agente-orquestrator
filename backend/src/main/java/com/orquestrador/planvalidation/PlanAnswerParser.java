package com.orquestrador.planvalidation;

/**
 * Lê a resposta CRUA do modelo local e a reduz a {@link Presence} + evidência. Módulo profundo
 * atrás de um seam mínimo: toda a tolerância a formato (o modelo fraco varia a redação) fica aqui,
 * e o resto da fatia só enxerga o veredito.
 *
 * <p><b>A regra que não se negocia:</b> o que não for reconhecível vira {@link
 * Presence#INDETERMINATE} — jamais {@link Presence#ABSENT}. Só assim uma falha de leitura deixa de
 * poder virar um buraco factual inventado no checklist do arquiteto.
 *
 * <p>O formato tolerado saiu de MEDIÇÃO, não de palpite (harness sobre o qwen3:1.7b): o veredito
 * vem na PRIMEIRA linha ({@code "SIM  \n\"trecho\""}), a evidência vem depois — às vezes entre
 * aspas, às vezes crua, e às vezes o modelo REPETE o bloco inteiro uma vez por tarefa do plano
 * (medido em {@code limpo/file-line}). Por isso o veredito é o PRIMEIRO reconhecido e a evidência
 * é o primeiro trecho não vazio depois dele.
 */
final class PlanAnswerParser {

    /** A evidência é citação, não despejo — o texto integral segue no plano e no StepTrace. */
    private static final int EVIDENCE_CAP_CHARS = 500;

    private PlanAnswerParser() {
    }

    static Answer parse(String content) {
        if (content == null || content.isBlank()) {
            return indeterminate();
        }
        String[] lines = content.strip().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Presence presence = verdictOf(lines[i]);
            if (presence != null) {
                return new Answer(presence, evidence(lines, i));
            }
        }
        return indeterminate();
    }

    /**
     * O veredito de UMA linha, ou {@code null} se ela não o carrega. Só conta no COMEÇO da linha:
     * "não" aparece o tempo todo no meio da evidência ("O texto não menciona nenhum arquivo") e
     * "sim" é sub-palavra de "assim" — casar solto inverteria vereditos.
     */
    private static Presence verdictOf(String line) {
        String limpa = line.replaceAll("^[^\\p{L}]+", "").toUpperCase().replace("NÃO", "NAO");
        limpa = limpa.startsWith("VEREDITO") ? limpa.replaceAll("^VEREDITO[:\\s]*", "") : limpa;
        // Eco do template ("SIM|NAO") — os DOIS rótulos juntos na cabeça da linha não decidem nada.
        String cabeca = limpa.substring(0, Math.min(20, limpa.length()));
        if (cabeca.contains("SIM") && cabeca.contains("NAO")) {
            return null;
        }
        boolean sim = limpa.startsWith("SIM");
        boolean nao = limpa.startsWith("NAO");
        if (sim == nao) {
            return null; // a linha não carrega veredito nenhum
        }
        return sim ? Presence.PRESENT : Presence.ABSENT;
    }

    /** O primeiro trecho não vazio depois do veredito (na mesma linha, se houver, senão abaixo). */
    private static String evidence(String[] lines, int verdictLine) {
        String mesmaLinha = lines[verdictLine].replaceAll("^[^\\p{L}]*(?i:VEREDITO[:\\s]*)?", "")
                .replaceAll("^(?i:SIM|NÃO|NAO)[.:!\\s]*", "").strip();
        if (!mesmaLinha.isBlank()) {
            return clean(mesmaLinha);
        }
        for (int i = verdictLine + 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return clean(lines[i]);
            }
        }
        return "";
    }

    private static String clean(String evidence) {
        String limpa = evidence.strip().replaceAll("^[\"«<]+", "").replaceAll("[\"»>]+$", "").strip();
        return limpa.length() <= EVIDENCE_CAP_CHARS ? limpa : limpa.substring(0, EVIDENCE_CAP_CHARS);
    }

    private static Answer indeterminate() {
        return new Answer(Presence.INDETERMINATE, "");
    }

    /** O par (veredito, evidência) que o validador carimba com o {@code checkId}. */
    record Answer(Presence presence, String evidence) {
    }
}
