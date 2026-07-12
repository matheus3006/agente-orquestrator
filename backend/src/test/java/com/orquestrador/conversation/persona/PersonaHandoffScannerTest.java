package com.orquestrador.conversation.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.org.agent.CeoPersona;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PINA O CONTRATO dos blocos tipados do roteador de personas (ADR-0031, M5 — formato proposto ao
 * dono, veto pendente): JSON numa CERCA EM COLUNA 0, irmão exato do ```proposta (parse
 * determinístico, zero LLM, malformado DESCARTADO fail-soft).
 *
 * <pre>```handoff
 * {"para": "planejador", "bastao": ".orq/conversa/&lt;conv&gt;/dossie.md", "resumo": "gerar estratégia"}
 * ```</pre>
 * <pre>```needs-grilling
 * {"lacuna": "qual o orçamento mensal aceitável?"}
 * ```</pre>
 *
 * A cerca INDENTADA não roteia — é assim que o exemplo do formato dentro do prompt (M7) nunca
 * dispara a virada quando um motor o ecoa/cita.
 */
class PersonaHandoffScannerTest {

    private final PersonaHandoffScanner scanner = new PersonaHandoffScanner();

    @Test
    void handoffCanonicoRoteiaParaAPersonaAlvo() {
        String reply = "Dossiê carimbado.\n\n```handoff\n"
                + "{\"para\": \"planejador\", \"bastao\": \".orq/conversa/abc/dossie.md\","
                + " \"resumo\": \"gerar estratégia\"}\n```\n";

        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);

        assertThat(routes).hasSize(1);
        PersonaHandoffScanner.Handoff handoff = (PersonaHandoffScanner.Handoff) routes.get(0);
        assertThat(handoff.para()).isEqualTo(CeoPersona.PLANEJADOR);
        assertThat(handoff.bastao()).isEqualTo(".orq/conversa/abc/dossie.md");
        assertThat(handoff.resumo()).isEqualTo("gerar estratégia");
    }

    @Test
    void needsGrillingCanonicoCarregaALacuna() {
        String reply = "Não dá para chutar.\n```needs-grilling\n"
                + "{\"lacuna\": \"qual o orçamento mensal aceitável?\"}\n```";

        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);

        assertThat(routes).hasSize(1);
        PersonaHandoffScanner.NeedsGrilling kickback = (PersonaHandoffScanner.NeedsGrilling) routes.get(0);
        assertThat(kickback.lacuna()).isEqualTo("qual o orçamento mensal aceitável?");
    }

    /** O exemplo do formato no prompt é INDENTADO — citá-lo/ecoá-lo nunca dispara a virada. */
    @Test
    void cercaIndentadaNaoRoteia() {
        String reply = "O formato é:\n  ```handoff\n  {\"para\": \"planejador\", \"bastao\": \"x\","
                + " \"resumo\": \"r\"}\n  ```\n  ```needs-grilling\n  {\"lacuna\": \"l\"}\n  ```\n";

        assertThat(scanner.scan(reply)).isEmpty();
    }

    /** Fail-soft (deny-by-default do roteamento): o que o core não entende não vira virada. */
    @Test
    void malformadoPersonaDesconhecidaOuLacunaAusenteSaoDescartados() {
        String naoJson = "```handoff\nisto não é json\n```";
        String personaDesconhecida = "```handoff\n{\"para\": \"tesoureiro\", \"bastao\": \"x\"}\n```";
        String placeholderDoPrompt = "```handoff\n{\"para\": \"<persona-alvo: planejador, executor ou"
                + " questionador>\", \"bastao\": \"x\"}\n```";
        String semLacuna = "```needs-grilling\n{\"motivo\": \"faltou contexto\"}\n```";

        assertThat(scanner.scan(naoJson)).isEmpty();
        assertThat(scanner.scan(personaDesconhecida)).isEmpty();
        assertThat(scanner.scan(placeholderDoPrompt)).isEmpty();
        assertThat(scanner.scan(semLacuna)).isEmpty();
        assertThat(scanner.scan(null)).isEmpty();
        assertThat(scanner.scan("resposta sem bloco nenhum")).isEmpty();
    }

    /**
     * O 3º irmão (caso B, ADR-0034/M2 — formato proposto ao dono, veto pendente): o Questionador
     * aponta a task ENTREGUE que ficou ruim com SÓ o número — as palavras do dono NUNCA viajam
     * no bloco (verbatim é cópia mecânica do banco, D3).
     */
    @Test
    void correcaoCanonicaCarregaSoONumero() {
        String reply = "Encaminho a correção da #12.\n\n```correcao\n{\"issue\": 12}\n```\n";

        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);

        assertThat(routes).hasSize(1);
        PersonaHandoffScanner.Correcao correcao = (PersonaHandoffScanner.Correcao) routes.get(0);
        assertThat(correcao.issue()).isEqualTo(12);
    }

    /** Fail-soft do correcao: número ausente/não numérico/não positivo ou cerca indentada = nada. */
    @Test
    void correcaoMalformadaOuIndentadaEDescartada() {
        assertThat(scanner.scan("```correcao\nisto não é json\n```")).isEmpty();
        assertThat(scanner.scan("```correcao\n{\"issue\": \"doze\"}\n```")).isEmpty();
        assertThat(scanner.scan("```correcao\n{\"task\": 12}\n```")).isEmpty();
        assertThat(scanner.scan("```correcao\n{\"issue\": 0}\n```")).isEmpty();
        assertThat(scanner.scan("```correcao\n{\"issue\": -3}\n```")).isEmpty();
        assertThat(scanner.scan("O formato é:\n  ```correcao\n  {\"issue\": 12}\n  ```\n"))
                .as("o exemplo INDENTADO no prompt do Questionador nunca dispara").isEmpty();
    }

    /**
     * O 4º irmão (absorção, ADR-0037/M3 — formato proposto ao dono, veto pendente): o Planejador
     * tria a issue órfã do board e aponta o TIME (nunca pessoa) por {@code dominio}/{@code foco}
     * SEPARADOS — nunca "Dev / Landing" numa string (dominio/foco são texto livre e podem conter
     * "/"). O {@code racional} é obrigatório: vira a evidência da Decision e a confirmação
     * visível (G1/G6 — racional invisível nunca mais).
     */
    @Test
    void absorcaoCanonicaCarregaIssueTimeERacional() {
        String reply = "Triei a #7.\n\n```absorcao\n{\"issue\": 7, \"dominio\": \"Dev\","
                + " \"foco\": \"Landing\", \"racional\": \"é trabalho de frontend\"}\n```\n";

        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);

        assertThat(routes).hasSize(1);
        PersonaHandoffScanner.Absorcao absorcao = (PersonaHandoffScanner.Absorcao) routes.get(0);
        assertThat(absorcao.issue()).isEqualTo(7);
        assertThat(absorcao.dominio()).isEqualTo("Dev");
        assertThat(absorcao.foco()).isEqualTo("Landing");
        assertThat(absorcao.racional()).isEqualTo("é trabalho de frontend");
    }

    /** Fail-soft do absorcao: malformado, campo faltando, N inválido ou cerca indentada = nada. */
    @Test
    void absorcaoMalformadaCampoFaltandoOuIndentadaEDescartada() {
        assertThat(scanner.scan("```absorcao\nisto não é json\n```")).isEmpty();
        assertThat(scanner.scan("```absorcao\n{\"issue\": \"sete\", \"dominio\": \"Dev\","
                + " \"foco\": \"Landing\", \"racional\": \"r\"}\n```")).isEmpty();
        assertThat(scanner.scan("```absorcao\n{\"issue\": 0, \"dominio\": \"Dev\","
                + " \"foco\": \"Landing\", \"racional\": \"r\"}\n```")).isEmpty();
        assertThat(scanner.scan("```absorcao\n{\"issue\": 7, \"foco\": \"Landing\","
                + " \"racional\": \"r\"}\n```")).as("sem dominio").isEmpty();
        assertThat(scanner.scan("```absorcao\n{\"issue\": 7, \"dominio\": \"Dev\","
                + " \"racional\": \"r\"}\n```")).as("sem foco").isEmpty();
        assertThat(scanner.scan("```absorcao\n{\"issue\": 7, \"dominio\": \"Dev\","
                + " \"foco\": \"Landing\"}\n```")).as("sem racional").isEmpty();
        assertThat(scanner.scan("O formato é:\n  ```absorcao\n  {\"issue\": 7, \"dominio\": \"Dev\","
                + " \"foco\": \"Landing\", \"racional\": \"r\"}\n  ```\n"))
                .as("o exemplo INDENTADO no prompt do Planejador nunca dispara").isEmpty();
    }

    // ---- F-N3 (ADR-0039, D2): a entrada ESTRUTURADA — o motor validou o shape na fonte, os blocos
    // chegam como JSON já parseado (campo `tipo` discrimina), sem regex. A validação de DOMÍNIO
    // (persona conhecida, #N >= 1, campos presentes) segue viva — é a mesma dos parsers do regex.

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
    void scanStructuredDespachaPorTipoNaOrdemDoArray() {
        List<PersonaHandoffScanner.Route> routes = scanner.scanStructured(blocos(
                "{\"tipo\":\"needs-grilling\",\"lacuna\":\"qual orçamento?\"}",
                "{\"tipo\":\"handoff\",\"para\":\"executor\",\"bastao\":\"estrategia.md\",\"resumo\":\"ok\"}",
                "{\"tipo\":\"absorcao\",\"issue\":7,\"dominio\":\"Dev\",\"foco\":\"Landing\",\"racional\":\"frontend\"}"));

        assertThat(routes).hasSize(3);
        assertThat(routes.get(0)).isInstanceOf(PersonaHandoffScanner.NeedsGrilling.class);
        assertThat(((PersonaHandoffScanner.Handoff) routes.get(1)).para()).isEqualTo(CeoPersona.EXECUTOR);
        assertThat(((PersonaHandoffScanner.Absorcao) routes.get(2)).foco()).isEqualTo("Landing");
    }

    @Test
    void scanStructuredIgnoraPropostaEBlocoDeDominioInvalido() {
        // proposta é do ProposalScanner (espelho do regex); persona desconhecida/campo faltando
        // seguem fail-soft (a validação de domínio sobrevive à migração do transporte).
        List<PersonaHandoffScanner.Route> routes = scanner.scanStructured(blocos(
                "{\"tipo\":\"proposta\",\"acao\":\"HIRE_AGENT\",\"alvo\":{}}",
                "{\"tipo\":\"handoff\",\"para\":\"tesoureiro\",\"bastao\":\"x\",\"resumo\":\"r\"}",
                "{\"tipo\":\"correcao\",\"issue\":0}"));

        assertThat(routes).isEmpty();
    }

    @Test
    void scanStructuredVazioOuNuloNaoRoteia() {
        assertThat(scanner.scanStructured(List.of())).isEmpty();
        assertThat(scanner.scanStructured(null)).isEmpty();
    }

    @Test
    void blocosValidosSaemNaOrdemDoTexto() {
        String reply = "```needs-grilling\n{\"lacuna\": \"primeira\"}\n```\n"
                + "```handoff\n{\"para\": \"executor\", \"bastao\": \"estrategia.md\", \"resumo\": \"ok\"}\n```\n";

        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);

        assertThat(routes).hasSize(2);
        assertThat(routes.get(0)).isInstanceOf(PersonaHandoffScanner.NeedsGrilling.class);
        assertThat(routes.get(1)).isInstanceOf(PersonaHandoffScanner.Handoff.class);
    }
}
