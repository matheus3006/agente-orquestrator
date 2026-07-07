package com.orquestrador.memory;

/**
 * O regime do racional (ADR-0020/D8): {@code LOGICO} BLOQUEIA o commit até razão substantiva
 * (premissa testável — reabre quando cai); {@code INTUICAO} COMMITA na hora (preferência legítima
 * — reabre quando o gosto muda; o probe é maiêutico, não-bloqueante). A classificação é DERIVADA
 * do artefato/área (D9), nunca escolhida pelo CEO ou pelo dono — menos gameável.
 */
public enum RacionalTipo {
    LOGICO,
    INTUICAO
}
