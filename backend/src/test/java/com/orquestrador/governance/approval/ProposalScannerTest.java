package com.orquestrador.governance.approval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O parser determinístico do canal de proposta (ADR-0018): blocos ```proposta na saída do CEO. */
class ProposalScannerTest {

    private final ProposalScanner scanner = new ProposalScanner();

    @Test
    void extraiBlocosValidosEIgnoraMalformados() {
        String reply = """
                Proponho contratar um executor para o time.

                ```proposta
                {"acao": "HIRE_AGENT", "alvo": {"role": "executor", "name": "Nix"}, "resumo": "Contratar Nix", "area": "estrutura-org"}
                ```

                E uma issue pro roadmap:

                ```proposta
                {"acao": "CREATE_ISSUE", "alvo": {"title": "Definir nicho"}, "resumo": "Issue de nicho"}
                ```

                Este aqui está quebrado e deve ser ignorado:

                ```proposta
                {"acao": "DROP_TABLES", "alvo": {}}
                ```

                ```proposta
                nem é json
                ```
                """;

        List<ProposalScanner.Proposal> proposals = scanner.scan(reply);

        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0).actionType()).isEqualTo(ApprovalActionType.HIRE_AGENT);
        // Alvo CANÔNICO: chaves ordenadas — o fingerprint de dedupe é estável entre re-emissões.
        assertThat(proposals.get(0).targetDetailJson()).isEqualTo("{\"name\":\"Nix\",\"role\":\"executor\"}");
        assertThat(proposals.get(0).resumo()).isEqualTo("Contratar Nix");
        assertThat(proposals.get(0).area()).isEqualTo("estrutura-org");
        assertThat(proposals.get(1).actionType()).isEqualTo(ApprovalActionType.CREATE_ISSUE);
        assertThat(proposals.get(1).area()).isNull(); // area é opcional no bloco
    }

    @Test
    void respostaSemBlocoNaoTemProposta() {
        assertThat(scanner.scan("Tudo certo, seguimos no plano combinado.")).isEmpty();
        assertThat(scanner.scan(null)).isEmpty();
        assertThat(scanner.scan("")).isEmpty();
    }

    @Test
    void alvoAusenteViraObjetoVazioEAcaoSemAlvoAindaVale() {
        List<ProposalScanner.Proposal> proposals = scanner.scan("""
                ```proposta
                {"acao": "WAKEUP_AGENT", "alvo": {"agentId": "3f1c0000-0000-4000-8000-000000000001"}, "resumo": "Acordar Echo"}
                ```
                """);
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).actionType()).isEqualTo(ApprovalActionType.WAKEUP_AGENT);
        assertThat(proposals.get(0).targetDetailJson()).contains("agentId");
    }
}
