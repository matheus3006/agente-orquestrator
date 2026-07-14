package com.orquestrador.memory;

/**
 * Os tiers DURÁVEIS da memória da empresa que moram em {@code company_memory} (ADR-0019/D3):
 * <ul>
 *   <li>{@code CARTA} — o sempre-on do CEO: projeção determinística das Decisões de direção
 *       vigentes (grau=DECISAO);</li>
 *   <li>{@code PLAYBOOK} — o tier durável ÚNICO da Frente C (C1, residual 2 da ADR-0019
 *       FECHADO): projeção determinística dos princípios selados (grau=PRINCIPIO). O
 *       durável-pontual já É o Decision-log — não existe tier "ADR-da-Empresa" separado.</li>
 * </ul>
 * Os demais tiers de D3 não vivem aqui: o episódico é o {@code activity_log}; o Ledger
 * por-Issue é read-model do run concluinte.
 */
public enum MemoryKind {
    CARTA,
    PLAYBOOK
}
