package com.orquestrador.work.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O limiar do gate FALHA O BOOT fora da escala 1-5 (achado da revisão adversarial da R.2):
 * limiar >= 6 é impossível de atingir (risk_level máximo é 5) → o gate desligaria SILENCIOSAMENTE
 * (fail-open de um mecanismo de segurança). Limiar 1 é válido ("revisar tudo").
 */
class ReviewThresholdGuardTest {

    @Test
    void limiarAcimaDaEscalaDerrubaOBoot() {
        assertThatThrownBy(() -> new IssueService(null, null, null, null, null, null, null, null, null, null, null, null, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk-threshold");
    }

    @Test
    void limiarAbaixoDaEscalaDerrubaOBoot() {
        assertThatThrownBy(() -> new IssueService(null, null, null, null, null, null, null, null, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limiaresDaEscalaConstroem() {
        assertThatCode(() -> {
            new IssueService(null, null, null, null, null, null, null, null, null, null, null, null, 1);
            new IssueService(null, null, null, null, null, null, null, null, null, null, null, null, 5);
        }).doesNotThrowAnyException();
    }
}
