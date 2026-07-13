package com.orquestrador.governance.approval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parser determinístico do canal de pedido-de-skill do EXECUTOR (F-AT1, grill agente-como-tool
 * D6): blocos ```pedido-skill na saída do Run — cerca SÓ em coluna 0 (mesma regra do
 * ProposalScanner/PersonaHandoffScanner); bloco malformado em coluna 0 é DESCARTADO e CONTADO
 * (anti-silêncio, o padrão da casa).
 */
class SkillRequestScannerTest {

    private final SkillRequestScanner scanner = new SkillRequestScanner();

    @Test
    void extraiPedidoValidoEContaOMalformadoDescartado() {
        String reply = """
                Preciso de uma skill que não está no meu set:

                ```pedido-skill
                {"skillKey": "tdd"}
                ```

                Este aqui está quebrado e deve ser descartado (e contado):

                ```pedido-skill
                nem é json
                ```
                """;

        SkillRequestScanner.Scan scan = scanner.scan(reply);

        List<SkillRequestScanner.SkillRequest> pedidos = scan.pedidos();
        assertThat(pedidos).hasSize(1);
        assertThat(pedidos.get(0).skillKey()).isEqualTo("tdd");
        assertThat(scan.descartadas()).isEqualTo(1);
    }

    @Test
    void respostaSemBlocoNaoTemPedidoNemDescarte() {
        assertThat(scanner.scan("Tudo certo, sigo com o que tenho.").pedidos()).isEmpty();
        assertThat(scanner.scan("Tudo certo, sigo com o que tenho.").descartadas()).isZero();
        assertThat(scanner.scan(null).pedidos()).isEmpty();
        assertThat(scanner.scan("").pedidos()).isEmpty();
    }

    /**
     * Cerca só vale em COLUNA 0 (mesma regra da ADR-0036): o exemplo do formato pode viajar
     * INDENTADO no prompt — um motor que o ecoa/cita não pode criar Approval fantasma.
     */
    @Test
    void cercaIndentadaECitacaoNaoViraPedidoNemDescarte() {
        String reply = """
                O formato do pedido é este:

                  ```pedido-skill
                  {"skillKey": "<key>"}
                  ```

                Emito quando precisar de verdade.
                """;

        SkillRequestScanner.Scan scan = scanner.scan(reply);

        assertThat(scan.pedidos()).isEmpty();
        assertThat(scan.descartadas()).isZero();
    }

    @Test
    void skillKeyAusenteOuEmBrancoEDescartado() {
        SkillRequestScanner.Scan scan = scanner.scan("""
                ```pedido-skill
                {"skillKey": "  "}
                ```

                ```pedido-skill
                {}
                ```
                """);

        assertThat(scan.pedidos()).isEmpty();
        assertThat(scan.descartadas()).isEqualTo(2);
    }
}
