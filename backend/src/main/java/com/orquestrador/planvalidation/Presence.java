package com.orquestrador.planvalidation;

/**
 * O veredito de UMA checagem sobre o plano-texto (Uso B, docs §2). Três valores de propósito: o
 * {@link #INDETERMINATE} existe para que "não deu para saber" (resposta vazia, truncada ou
 * ilegível do modelo local) NUNCA seja confundido com {@link #ABSENT} — confundir os dois vira
 * falso negativo sistemático, o modo de falha que o kickoff mandou cobrir e que foi medido
 * acontecendo (orçamento de tokens curto ⇒ o raciocínio consome tudo e a resposta vem vazia).
 */
public enum Presence {

    /** O plano satisfaz a checagem — o modelo afirmou e apontou o trecho. */
    PRESENT,

    /** O plano NÃO satisfaz a checagem — é este veredito que vira buraco factual no checklist. */
    ABSENT,

    /** Não deu para saber. Nunca é empurrado como buraco; entra na telemetria como ruído medido. */
    INDETERMINATE
}
