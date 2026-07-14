package com.orquestrador.memory;

/**
 * O eixo do ALCANCE da Decisão (Frente C, C3 — emenda ADR-0019): {@code DECISAO} resolve UM
 * caso; {@code PRINCIPIO} governa casos futuros. Eixo NOVO, ortogonal ao {@link DecisionTipo}
 * (o eixo do ASSUNTO, derivado da área): colapsar os dois faria o Log filtrável mentir
 * ({@code ?tipo=TECNICA} perderia todo princípio técnico). {@code grau} é derivado da PORTA,
 * {@code tipo} da ÁREA — nenhum é julgado (D9).
 *
 * <p>{@code PRINCIPIO} entrou JUNTO do seu writer — o ritual de graduação (F-C2, C6): um
 * princípio nasce APENAS pelo gate ({@code GRADUATE_PRINCIPLE} — o CEO propõe a frase, o dono
 * sela) e é o que a 2ª projeção ({@link MemoryKind#PLAYBOOK}) materializa.
 */
public enum DecisionGrau {
    DECISAO,
    PRINCIPIO
}
