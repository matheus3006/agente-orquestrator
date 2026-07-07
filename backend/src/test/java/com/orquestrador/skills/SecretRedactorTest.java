package com.orquestrador.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Segredo nunca toca o banco (LGPD): a redacao mascara credenciais e atribuicoes sensiveis. */
class SecretRedactorTest {

    @Test
    void mascaraAtribuicaoComChaveSensivel() {
        String out = SecretRedactor.redact("password: hunter2supersecretvalue");
        assertThat(out).doesNotContain("hunter2supersecretvalue").contains(SecretRedactor.MASK);
        // a chave em si permanece (so o valor e mascarado)
        assertThat(out).contains("password:");
    }

    @Test
    void mascaraFormatosDeCredencialEmQualquerPosicao() {
        assertThat(SecretRedactor.redact("rode com api_key: sk-LIVEsecretABCDEFGHIJ no shell"))
                .doesNotContain("sk-LIVEsecretABCDEFGHIJ");
        assertThat(SecretRedactor.redact("export GH=ghp_ABCDEFGHIJKLMNOPQRSTUVWX1234"))
                .doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUVWX1234");
        assertThat(SecretRedactor.redact("aws AKIA1234567890ABCDEF fim"))
                .doesNotContain("AKIA1234567890ABCDEF");
        assertThat(SecretRedactor.redact("Authorization: Bearer abcdef1234567890token"))
                .doesNotContain("abcdef1234567890token");
    }

    @Test
    void mascaraSegredoMultilinhaEFormatosEstruturados() {
        // valor em bloco multilinha sob chave sensivel (o segredo esta na linha seguinte indentada)
        assertThat(SecretRedactor.redact("api_key:\n  blocoSecretoValor123\n"))
                .doesNotContain("blocoSecretoValor123").contains(SecretRedactor.MASK);

        // bloco PEM de chave privada
        assertThat(SecretRedactor.redact(
                "-----BEGIN PRIVATE KEY-----\nMIIBVwIBADANBgkqhkiG9w0\n-----END PRIVATE KEY-----"))
                .doesNotContain("MIIBVwIBADANBgkqhkiG9w0").contains(SecretRedactor.MASK);

        // JWT
        assertThat(SecretRedactor.redact(
                "cookie eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3In0.SflKxwRJSMeKKF2QT4fwpM"))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9");

        // credencial embutida em URL (DSN)
        assertThat(SecretRedactor.redact("DB_DSN deploy: postgres://admin:topsecretpw@db.host:5432/app"))
                .doesNotContain("topsecretpw");
    }

    @Test
    void mascaraSegredoMesmoComFimDeLinhaCRLF() {
        // arquivo Windows (CRLF): sem normalizar, o '\r' faria a atribuicao sensivel nao casar e vazar
        String out = SecretRedactor.redact("intro\r\npassword: hunter2SuperSecretValue\r\nfim\r\n");
        assertThat(out).doesNotContain("hunter2SuperSecretValue").contains(SecretRedactor.MASK);
    }

    @Test
    void prosaNormalSobrevive() {
        String prose = "Usa o algoritmo de token bucket para limitar a taxa de requisicoes.";
        assertThat(SecretRedactor.redact(prose)).isEqualTo(prose);
        assertThat(SecretRedactor.redact("## Authentication flow")).contains("Authentication flow");
    }
}
