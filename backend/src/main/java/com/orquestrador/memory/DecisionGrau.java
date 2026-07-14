package com.orquestrador.memory;

/**
 * O eixo do ALCANCE da Decisão (Frente C, C3 — emenda ADR-0019): {@code DECISAO} resolve UM
 * caso. Eixo NOVO, ortogonal ao {@link DecisionTipo} (o eixo do ASSUNTO, derivado da área):
 * colapsar os dois faria o Log filtrável mentir ({@code ?tipo=TECNICA} perderia todo princípio
 * técnico). {@code grau} é derivado da PORTA, {@code tipo} da ÁREA — nenhum é julgado (D9).
 *
 * <p>O valor {@code PRINCIPIO} (governa casos futuros) entra JUNTO do seu writer — o ritual de
 * graduação (F-C2): enum entra com o consumidor, nunca antes (seam morto é a doença que a
 * Frente C cura).
 */
public enum DecisionGrau {
    DECISAO
}
