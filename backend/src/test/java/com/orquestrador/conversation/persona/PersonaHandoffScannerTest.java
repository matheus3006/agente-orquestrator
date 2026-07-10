package com.orquestrador.conversation.persona;

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
