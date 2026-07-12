package com.orquestrador.conversation;

import com.orquestrador.org.agent.CeoPersona;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O CONTRATO da saída estruturada nativa (F-N3/ADR-0039, D2 do grill motor-nativo): o schema
 * JSON que PRENDE a saída de um turno de persona a {@code {resposta, blocos[]}} — o motor valida
 * na fonte (claude {@code --json-schema}, codex {@code --output-schema}). Provado ao vivo nos 2
 * motores antes de escrever o código (sondas: codex força strict OpenAI, aceita anyOf+alvo aninhado).
 */
class TurnOutputSchemaTest {

    private final TurnOutputSchema schema = new TurnOutputSchema();

    @Test
    void schemaDoExecutorEBemFormadoEPrendeRespostaEBlocos() {
        String json = schema.forPersona(CeoPersona.EXECUTOR);

        // O cinto obrigatório (gotcha: claude <2.1.205 IGNORA schema inválido em silêncio — o pino
        // é 2.1.201): o que o builder emite tem de passar pela própria validação, sempre.
        assertThatCode(() -> schema.requireWellFormed(json)).doesNotThrowAnyException();
        // O contrato é {resposta, blocos}: os DOIS campos, nada além (strict do codex).
        assertThat(json).contains("\"resposta\"").contains("\"blocos\"");
    }

    @Test
    void parseExtraiRespostaEBlocosDaVoltaValidada() {
        // A forma REAL que os 2 motores devolvem (provado ao vivo): resposta + blocos[] no result.
        String reply = "{\"resposta\":\"Segue a estratégia.\",\"blocos\":["
                + "{\"tipo\":\"handoff\",\"para\":\"executor\",\"bastao\":\"estrategia.md\",\"resumo\":\"ok\"}]}";

        TurnOutputSchema.ParsedTurnOutput parsed = schema.parse(reply);

        assertThat(parsed.malformado()).isFalse();
        assertThat(parsed.resposta()).isEqualTo("Segue a estratégia.");
        assertThat(parsed.blocos()).hasSize(1);
        assertThat(parsed.blocos().get(0).path("tipo").asText()).isEqualTo("handoff");
    }

    @Test
    void parseMarcaMalformadoEGuardaOTextoCruQuandoAVoltaNaoBateOContrato() {
        // Sem schema (claude <2.1.205 ignorou em silêncio) OU motor cuspindo prosa: NUNCA some —
        // marca malformado e a resposta vira o texto cru (o coordinator avisa alto; o dono vê algo).
        assertThat(schema.parse("isto não é json").malformado()).isTrue();
        assertThat(schema.parse("isto não é json").resposta()).isEqualTo("isto não é json");
        assertThat(schema.parse("{\"blocos\":[]}").malformado()).as("sem resposta").isTrue();
        assertThat(schema.parse("{\"resposta\":\"oi\"}").malformado()).as("sem blocos").isTrue();
        assertThat(schema.parse(null).malformado()).isTrue();
        assertThat(schema.parse("").malformado()).isTrue();
    }

    @Test
    void beltRejeitaSchemaForaDoContratoStrict() {
        // O cinto que a gotcha exige: um schema que o claude <2.1.205 ignoraria em silêncio FALHA
        // ALTO aqui. Sem JSON, sem strict (additionalProperties ausente/true), sem o contrato.
        assertThatThrownBy(() -> schema.requireWellFormed("{ não json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> schema.requireWellFormed("{\"type\":\"object\",\"properties\":{}}"))
                .as("sem additionalProperties:false (o codex rejeita, o claude<2.1.205 ignora)")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> schema.requireWellFormed(
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"))
                .as("sem o contrato {resposta,blocos}")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void schemaPorPersonaSoCarregaOsBlocosQueAPersonaEmite() {
        // O caso C (ADR-0036) vira ESTRUTURALMENTE IMPOSSÍVEL: o Questionador não pode emitir
        // proposta porque o schema dele nem a contém — o motor valida na fonte.
        assertThat(schema.forPersona(CeoPersona.QUESTIONADOR))
                .contains("\"handoff\"").contains("\"correcao\"")
                .doesNotContain("\"proposta\"").doesNotContain("\"absorcao\"");
        // O Executor protocola o gate: proposta com as DUAS ações (HIRE_AGENT + CREATE_TEAM).
        assertThat(schema.forPersona(CeoPersona.EXECUTOR))
                .contains("\"proposta\"").contains("HIRE_AGENT").contains("CREATE_TEAM")
                .doesNotContain("\"correcao\"").doesNotContain("\"absorcao\"");
        // O Planejador tria o board: absorcao é só dele.
        assertThat(schema.forPersona(CeoPersona.PLANEJADOR))
                .contains("\"absorcao\"").contains("\"handoff\"")
                .doesNotContain("\"proposta\"").doesNotContain("\"correcao\"");
    }
}
