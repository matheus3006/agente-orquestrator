package com.orquestrador.execution.lineage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O VO da cadeia de causadores (T.4, ADR-0016 rev. 2026-07-04): quem causou este trabalho.
 * O formato da coluna é PRIVADO do módulo — callers só conhecem root/decode/encoded/descendTo/
 * contains/causer/depth. Raiz = ato humano direto (operador/dono).
 */
class SpawnLineageTest {

    private final UUID ceo = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID lider = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID executor = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void raizEhVaziaSemCausadorESemAncestrais() {
        SpawnLineage raiz = SpawnLineage.root();

        assertThat(raiz.depth()).isZero();
        assertThat(raiz.causer()).isNull();
        assertThat(raiz.contains(ceo)).isFalse();
        assertThat(raiz.encoded()).isEmpty();
    }

    @Test
    void descendToEmpilhaOCausadorNaCadeia() {
        SpawnLineage doCeo = SpawnLineage.root().descendTo(ceo);
        SpawnLineage doLider = doCeo.descendTo(lider);

        assertThat(doCeo.depth()).isEqualTo(1);
        assertThat(doCeo.causer()).isEqualTo(ceo);

        assertThat(doLider.depth()).isEqualTo(2);
        assertThat(doLider.causer()).isEqualTo(lider);
        assertThat(doLider.contains(ceo)).isTrue();
        assertThat(doLider.contains(lider)).isTrue();
        assertThat(doLider.contains(executor)).isFalse();
    }

    @Test
    void decodeDoEncodedPreservaACadeia() {
        SpawnLineage original = SpawnLineage.root().descendTo(ceo).descendTo(lider);

        SpawnLineage reidratada = SpawnLineage.decode(original.encoded());

        assertThat(reidratada.depth()).isEqualTo(2);
        assertThat(reidratada.causer()).isEqualTo(lider);
        assertThat(reidratada.contains(ceo)).isTrue();
    }

    @Test
    void decodeDeColunaVaziaOuNulaEhRaiz() {
        assertThat(SpawnLineage.decode("").depth()).isZero();
        assertThat(SpawnLineage.decode(null).depth()).isZero();
        assertThat(SpawnLineage.decode("  ").causer()).isNull();
    }
}
