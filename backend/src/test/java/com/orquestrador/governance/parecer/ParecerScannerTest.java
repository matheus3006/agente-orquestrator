package com.orquestrador.governance.parecer;

import com.orquestrador.work.review.ReviewDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parse determinístico do bloco {@code ```parecer```} (molde F-AT1, irmão do
 * {@link com.orquestrador.governance.approval.DirigirScanner}): cerca em coluna 0, campos
 * obrigatórios, descarte CONTADO (anti-silêncio). A issue é endereçada pelo {@code #N} humano
 * (ADR-0033) — o LLM sabe o #N, alucina UUID (mesmo racional do {@code alvo} por nome no dirigir).
 */
class ParecerScannerTest {

    private final ParecerScanner scanner = new ParecerScanner();

    private static String bloco(String corpo) {
        return "texto antes\n```parecer\n" + corpo + "\n```\ntexto depois\n";
    }

    @Test
    void blocoCompleto_viraParecerComSeqDecisaoETexto() {
        ParecerScanner.Scan scan = scanner.scan(bloco(
                "{\"issue\": 42, \"decisao\": \"APROVAR\", \"parecer\": \"cobre o 409 e o rastro\"}"));

        assertThat(scan.descartados()).isZero();
        assertThat(scan.pareceres()).hasSize(1);
        ParecerScanner.Parecer parecer = scan.pareceres().get(0);
        assertThat(parecer.issueSeq()).isEqualTo(42);
        assertThat(parecer.decisao()).isEqualTo(ReviewDecision.APPROVE);
        assertThat(parecer.texto()).isEqualTo("cobre o 409 e o rastro");
    }

    @Test
    void rejeitar_mapeiaParaReject() {
        ParecerScanner.Scan scan = scanner.scan(bloco(
                "{\"issue\": 7, \"decisao\": \"REJEITAR\", \"parecer\": \"sem teste do caminho triste\"}"));

        assertThat(scan.pareceres()).singleElement()
                .extracting(ParecerScanner.Parecer::decisao).isEqualTo(ReviewDecision.REJECT);
    }

    @Test
    void semTextoDoParecer_descartaContado() {
        ParecerScanner.Scan scan = scanner.scan(bloco("{\"issue\": 42, \"decisao\": \"APROVAR\"}"));

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isEqualTo(1);
    }

    @Test
    void decisaoForaDoPar_descartaContado() {
        ParecerScanner.Scan scan = scanner.scan(bloco(
                "{\"issue\": 42, \"decisao\": \"TALVEZ\", \"parecer\": \"em cima do muro\"}"));

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isEqualTo(1);
    }

    @Test
    void semIssue_descartaContado() {
        ParecerScanner.Scan scan = scanner.scan(bloco(
                "{\"decisao\": \"APROVAR\", \"parecer\": \"boa entrega\"}"));

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isEqualTo(1);
    }

    @Test
    void issueNaoPositiva_descartaContado() {
        ParecerScanner.Scan scan = scanner.scan(bloco(
                "{\"issue\": 0, \"decisao\": \"APROVAR\", \"parecer\": \"boa entrega\"}"));

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isEqualTo(1);
    }

    @Test
    void blocoNaoJson_descartaContado() {
        ParecerScanner.Scan scan = scanner.scan(bloco("eu aprovaria, porque ficou bom"));

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isEqualTo(1);
    }

    @Test
    void cercaIndentada_naoEBloco() {
        ParecerScanner.Scan scan = scanner.scan(
                "  ```parecer\n{\"issue\": 1, \"decisao\": \"APROVAR\", \"parecer\": \"x\"}\n  ```\n");

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isZero();
    }

    @Test
    void replySemBloco_naoVarreNada() {
        ParecerScanner.Scan scan = scanner.scan("entreguei a issue, subi os testes");

        assertThat(scan.pareceres()).isEmpty();
        assertThat(scan.descartados()).isZero();
    }

    @Test
    void doisBlocos_ambosVarridos() {
        ParecerScanner.Scan scan = scanner.scan(
                "```parecer\n{\"issue\": 1, \"decisao\": \"APROVAR\", \"parecer\": \"ok\"}\n```\n"
                        + "```parecer\n{\"issue\": 2, \"decisao\": \"REJEITAR\", \"parecer\": \"nao\"}\n```\n");

        assertThat(scan.pareceres()).hasSize(2);
        assertThat(scan.pareceres()).extracting(ParecerScanner.Parecer::issueSeq)
                .containsExactly(1, 2);
    }
}
