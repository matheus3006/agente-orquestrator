package com.orquestrador.governance.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parser determinístico do canal de proposta (ADR-0018, emendada pela ADR-0036): blocos
 * ```proposta na saída do CEO — cerca SÓ em coluna 0 (simetria com o PersonaHandoffScanner);
 * bloco malformado em coluna 0 é DESCARTADO e CONTADO (o settle avisa o dono — anti-silêncio).
 */
class ProposalScannerTest {

    private final ProposalScanner scanner = new ProposalScanner();

    @Test
    void extraiBlocosValidosEContaOsMalformadosDescartados() {
        String reply = """
                Proponho contratar um executor para o time.

                ```proposta
                {"acao": "HIRE_AGENT", "alvo": {"role": "executor", "name": "Nix"}, "resumo": "Contratar Nix", "area": "estrutura-org"}
                ```

                E uma issue pro roadmap:

                ```proposta
                {"acao": "CREATE_ISSUE", "alvo": {"title": "Definir nicho"}, "resumo": "Issue de nicho"}
                ```

                Este aqui está quebrado e deve ser descartado (e contado):

                ```proposta
                {"acao": "DROP_TABLES", "alvo": {}}
                ```

                ```proposta
                nem é json
                ```
                """;

        ProposalScanner.Scan scan = scanner.scan(reply);

        List<ProposalScanner.Proposal> proposals = scan.propostas();
        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0).actionType()).isEqualTo(ApprovalActionType.HIRE_AGENT);
        // Alvo CANÔNICO: chaves ordenadas — o fingerprint de dedupe é estável entre re-emissões.
        assertThat(proposals.get(0).targetDetailJson()).isEqualTo("{\"name\":\"Nix\",\"role\":\"executor\"}");
        assertThat(proposals.get(0).resumo()).isEqualTo("Contratar Nix");
        assertThat(proposals.get(0).area()).isEqualTo("estrutura-org");
        assertThat(proposals.get(1).actionType()).isEqualTo(ApprovalActionType.CREATE_ISSUE);
        assertThat(proposals.get(1).area()).isNull(); // area é opcional no bloco
        // O anti-silêncio da ADR-0036 nasce desta contagem: descarte deixa de ser invisível.
        assertThat(scan.descartadas()).isEqualTo(2);
    }

    @Test
    void respostaSemBlocoNaoTemPropostaNemDescarte() {
        assertThat(scanner.scan("Tudo certo, seguimos no plano combinado.").propostas()).isEmpty();
        assertThat(scanner.scan("Tudo certo, seguimos no plano combinado.").descartadas()).isZero();
        assertThat(scanner.scan(null).propostas()).isEmpty();
        assertThat(scanner.scan("").propostas()).isEmpty();
    }

    @Test
    void alvoAusenteViraObjetoVazioEAcaoSemAlvoAindaVale() {
        List<ProposalScanner.Proposal> proposals = scanner.scan("""
                ```proposta
                {"acao": "WAKEUP_AGENT", "alvo": {"agentId": "3f1c0000-0000-4000-8000-000000000001"}, "resumo": "Acordar Echo"}
                ```
                """).propostas();
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).actionType()).isEqualTo(ApprovalActionType.WAKEUP_AGENT);
        assertThat(proposals.get(0).targetDetailJson()).contains("agentId");
    }

    /**
     * ADR-0036: cerca só vale em COLUNA 0 — o exemplo do formato viaja INDENTADO no prompt do
     * Executor (JSON válido com acao HIRE_AGENT!); um motor que o ecoa/cita não pode criar
     * Approval fantasma no gate, nem disparar o aviso de descarte. Espaço = citação, margem =
     * emissão (a regra já pinada no irmão PersonaHandoffScannerTest).
     */
    @Test
    void cercaIndentadaECitacaoNaoViraPropostaNemDescarte() {
        String reply = """
                O formato do protocolo é este:

                  ```proposta
                  {"acao": "HIRE_AGENT", "alvo": {"name": "<nome>", "role": "<role>"}, "resumo": "<uma linha>"}
                  ```

                Emito quando a estratégia chegar.
                """;

        ProposalScanner.Scan scan = scanner.scan(reply);

        assertThat(scan.propostas()).isEmpty();
        assertThat(scan.descartadas()).isZero();
    }

    // ---- F-N3 (ADR-0039, D2): a entrada ESTRUTURADA — o motor validou o shape na fonte (o caso C
    // vira impossível: o schema do Questionador nem contém proposta). O gate segue igual: só o bloco
    // com tipo=proposta vira Approval; o alvo continua re-serializado canônico p/ o fingerprint.

    private final ObjectMapper mapper = new ObjectMapper();

    private List<JsonNode> blocos(String... jsons) {
        return java.util.Arrays.stream(jsons).map(json -> {
            try {
                return (JsonNode) mapper.readTree(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

    @Test
    void scanStructuredColhePropostaValidadaEIgnoraOsOutrosBlocos() {
        ProposalScanner.Scan scan = scanner.scanStructured(blocos(
                "{\"tipo\":\"proposta\",\"acao\":\"HIRE_AGENT\",\"alvo\":{\"role\":\"executor\","
                        + "\"name\":\"Nix\"},\"resumo\":\"Contratar Nix\",\"area\":\"estrutura-org\"}",
                "{\"tipo\":\"handoff\",\"para\":\"executor\",\"bastao\":\"x\",\"resumo\":\"r\"}"));

        assertThat(scan.propostas()).hasSize(1);
        assertThat(scan.propostas().get(0).actionType()).isEqualTo(ApprovalActionType.HIRE_AGENT);
        // O alvo segue re-serializado CANÔNICO (chaves ordenadas) — o fingerprint de dedupe intacto.
        assertThat(scan.propostas().get(0).targetDetailJson()).isEqualTo("{\"name\":\"Nix\",\"role\":\"executor\"}");
        assertThat(scan.propostas().get(0).area()).isEqualTo("estrutura-org");
        assertThat(scan.descartadas()).isZero(); // o handoff não é proposta — ignorado, não descartado
    }

    @Test
    void scanStructuredVazioOuNuloNaoColhe() {
        assertThat(scanner.scanStructured(List.of()).propostas()).isEmpty();
        assertThat(scanner.scanStructured(null).propostas()).isEmpty();
        assertThat(scanner.scanStructured(null).descartadas()).isZero();
    }
}
