package com.orquestrador.work.issue;

import com.orquestrador.org.team.Team;

/**
 * As mensagens-SENTINELA da absorção (ADR-0037, M2/M8) — ZERO migração, como a
 * {@code ContinuationSentinel} da ADR-0030: o turno nasce {@code conversation_turn} normal cujo
 * {@code owner_message} É a sentinela. Ambas começam com {@code [continuação} de propósito — a
 * thread do board as ESCONDE pelo MESMO filtro (D4: gatilho e retoma são internos; o que o dono
 * vê são as confirmações/avisos do protocolo). Quem reivindica o turno é o PLANEJADOR (D3).
 */
public final class AbsorcaoSentinel {

    private static final String PREFIX = "[continuação — absorção da issue";

    private AbsorcaoSentinel() {
    }

    /**
     * O turno-GATILHO (D1): a issue do dono acabou de nascer órfã no board. Carrega #N + título +
     * descrição VERBATIM — o insumo da triagem; a instrução do que fazer mora no prompt do
     * Planejador (M10), não aqui.
     */
    public static String gatilho(Issue issue) {
        String descricao = issue.getDescription() == null || issue.getDescription().isBlank()
                ? "" : " Descrição do dono, verbatim: " + issue.getDescription() + ".";
        return PREFIX + " #" + issue.getSeq() + " — o dono criou \"" + issue.getTitle()
                + "\" no board, sem dono." + descricao
                + " Trie contra a equipe e aponte o time no bloco absorcao]";
    }

    /**
     * O turno de RETOMA (D7/M8): o APPROVE do {@code CREATE_TEAM} acabou de criar o time (com
     * arquiteto) que faltava — o Planejador retoma a triagem da #N parada.
     */
    public static String retoma(Issue issue, Team time) {
        return PREFIX + " #" + issue.getSeq() + " — o time " + time.getDominio() + " / "
                + time.getFoco() + " agora existe (aprovado no gate); retome a triagem da #"
                + issue.getSeq() + " e aponte o time no bloco absorcao]";
    }
}
