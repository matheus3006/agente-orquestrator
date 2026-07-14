package com.orquestrador.memory;

import java.util.List;

/**
 * QUEM recebe o sempre-on — e portanto QUAL dieta da memória durável é materializada no workspace
 * (F-C3, C9: o SPLIT do always-on). As duas pontas leem a MESMA memória; a dieta é que difere:
 *
 * <ul>
 *   <li>{@link #CEO} — <b>carta + princípios</b>. É o dono do rumo: as direções vigentes são o
 *       material dele.</li>
 *   <li>{@link #EXECUTOR} — <b>princípios SÓ</b>. Direção do dono <b>não é material de executor</b>
 *       (ele executa uma Issue, não conduz a empresa). Antes da F-C3 o executor não recebia memória
 *       NENHUMA — o D12 ("os agentes APLICAM") era letra morta e o agente sofria
 *       <i>cegueira-de-retrieval</i>: sem nada sempre-on, nunca lhe ocorre PROCURAR.</li>
 * </ul>
 *
 * <p>Os <b>princípios são o índice enxuto</b> do método (frases terças, dono-seladas) — indexar um
 * índice seria absurdo. Cada linha carrega o endereço {@code #N}, que é exatamente a chave do
 * {@code memory_fetch}: a dieta sempre-on ENSINA o trilho JIT de graça.
 */
public enum MemoryAudience {

    /** Turno do CEO: as direções vigentes + os princípios selados. */
    CEO(List.of(MemoryKind.CARTA, MemoryKind.PLAYBOOK)),

    /** Run de executor: só os princípios (o índice). A carta fica fora. */
    EXECUTOR(List.of(MemoryKind.PLAYBOOK));

    private final List<MemoryKind> dieta;

    MemoryAudience(List<MemoryKind> dieta) {
        this.dieta = dieta;
    }

    /** As projeções que entram no arquivo sempre-on desta ponta, na ordem de leitura. */
    public List<MemoryKind> dieta() {
        return dieta;
    }
}
