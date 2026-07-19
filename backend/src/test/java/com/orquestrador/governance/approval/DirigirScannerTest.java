package com.orquestrador.governance.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O scanner do bloco {@code ```dirigir```} (ADR-0047, D-DSG-1/4 — irmão do
 * {@link SkillRequestScanner}, molde F-AT1): parse determinístico com as regras da ADR-0036 —
 * cerca só em coluna 0, descarte CONTADO. O RACIONAL é obrigatório (D-DSG-4): bloco sem o porquê
 * é descartado, nunca vira ordem.
 */
class DirigirScannerTest {

    private final DirigirScanner scanner = new DirigirScanner();

    @Test
    void blocoCompletoViraOrdem() {
        DirigirScanner.Scan scan = scanner.scan("""
                Analisei o backlog do braço.

                ```dirigir
                {"alvo": "Bruno", "titulo": "Calendário de posts", "descricao": "Semana 30",
                 "risco": 2, "racional": "Bruno domina o calendário e a pauta está fria"}
                ```
                """);

        assertThat(scan.descartadas()).isZero();
        assertThat(scan.ordens()).containsExactly(new DirigirScanner.Ordem(
                "Bruno", "Calendário de posts", "Semana 30", 2,
                "Bruno domina o calendário e a pauta está fria"));
    }

    @Test
    void riscoAusenteValeUm() {
        DirigirScanner.Scan scan = scanner.scan("""
                ```dirigir
                {"alvo": "Bruno", "titulo": "Pauta", "racional": "pauta parada"}
                ```
                """);

        assertThat(scan.ordens()).hasSize(1);
        assertThat(scan.ordens().get(0).risco()).isEqualTo(1);
        assertThat(scan.ordens().get(0).descricao()).isNull();
    }

    @Test
    void semRacionalEDescartadoContado() {
        // D-DSG-4: o porquê é obrigatório — a mesma régua que o gate aplica ao dono no decide.
        DirigirScanner.Scan scan = scanner.scan("""
                ```dirigir
                {"alvo": "Bruno", "titulo": "Pauta"}
                ```
                """);

        assertThat(scan.ordens()).isEmpty();
        assertThat(scan.descartadas()).isEqualTo(1);
    }

    @Test
    void semAlvoOuTituloEDescartado() {
        DirigirScanner.Scan scan = scanner.scan("""
                ```dirigir
                {"titulo": "Pauta", "racional": "r"}
                ```

                ```dirigir
                {"alvo": "Bruno", "racional": "r"}
                ```
                """);

        assertThat(scan.ordens()).isEmpty();
        assertThat(scan.descartadas()).isEqualTo(2);
    }

    @Test
    void riscoForaDaEscalaEDescartadoNaoClampado() {
        DirigirScanner.Scan scan = scanner.scan("""
                ```dirigir
                {"alvo": "Bruno", "titulo": "Pauta", "risco": 9, "racional": "r"}
                ```
                """);

        assertThat(scan.ordens()).isEmpty();
        assertThat(scan.descartadas()).isEqualTo(1);
    }

    @Test
    void cercaIndentadaECitacaoNaoOrdem() {
        // ADR-0036: espaço = citação, margem = emissão — o exemplo do prompt não dirige ninguém.
        DirigirScanner.Scan scan = scanner.scan("""
                O formato é:

                  ```dirigir
                  {"alvo": "Exemplo", "titulo": "Exemplo", "racional": "exemplo"}
                  ```
                """);

        assertThat(scan.ordens()).isEmpty();
        assertThat(scan.descartadas()).isZero();
    }

    @Test
    void naoJsonEDescartadoContado() {
        DirigirScanner.Scan scan = scanner.scan("""
                ```dirigir
                dirija o Bruno pra pauta
                ```
                """);

        assertThat(scan.ordens()).isEmpty();
        assertThat(scan.descartadas()).isEqualTo(1);
    }

    @Test
    void replyVazioOuSemBlocoNaoTemNada() {
        assertThat(scanner.scan(null).ordens()).isEmpty();
        assertThat(scanner.scan("relatório sem bloco").ordens()).isEmpty();
        assertThat(scanner.scan("relatório sem bloco").descartadas()).isZero();
    }
}
