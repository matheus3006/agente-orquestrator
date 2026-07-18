package com.orquestrador.planvalidation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A leitura da resposta do modelo local. A regra que MAIS importa aqui é a do INDETERMINADO:
 * resposta vazia/truncada NUNCA pode virar "check ausente" — seria falso negativo sistemático,
 * e foi medido acontecendo de verdade (harness: com {@code num_predict=150} o raciocínio do
 * qwen3 comeu o orçamento e 14 de 18 respostas vieram VAZIAS, {@code done_reason=length}).
 */
class PlanAnswerParserTest {

    @Test
    void respostaVaziaViraIndeterminado() {
        assertThat(PlanAnswerParser.parse("").presence()).isEqualTo(Presence.INDETERMINATE);
    }

    /** Saída VERBATIM do qwen3:1.7b no harness (caso violado/seam, verdade=presente). */
    @Test
    void simViraPresenteComAEvidenciaCitada() {
        PlanAnswerParser.Answer answer = PlanAnswerParser.parse(
                "SIM  \n\"A mudança entra no serviço de relatórios (`ReportService`), no ponto"
                        + " onde o saldo é agregado.\"");

        assertThat(answer.presence()).isEqualTo(Presence.PRESENT);
        assertThat(answer.evidence()).isEqualTo("A mudança entra no serviço de relatórios"
                + " (`ReportService`), no ponto onde o saldo é agregado.");
    }

    /**
     * Saída VERBATIM (caso violado/file-line, verdade=ausente) — e a armadilha: a evidência
     * CONTÉM "não". Casar o veredito solto no texto inverteria a leitura.
     */
    @Test
    void naoViraAusenteAindaQueAEvidenciaContenhaAPalavraNao() {
        PlanAnswerParser.Answer answer = PlanAnswerParser.parse(
                "NÃO  \nO texto não menciona nenhum arquivo ou linha concreta em relação às"
                        + " tarefas descritas.");

        assertThat(answer.presence()).isEqualTo(Presence.ABSENT);
        assertThat(answer.evidence()).startsWith("O texto não menciona nenhum arquivo");
    }

    /**
     * Saída VERBATIM (caso limpo/file-line): o modelo REPETIU o bloco uma vez por tarefa do plano.
     * O primeiro veredito vence e a evidência é só a primeira — o registro é citação, não despejo.
     */
    @Test
    void blocoRepetidoUsaOPrimeiroVeredito() {
        PlanAnswerParser.Answer answer = PlanAnswerParser.parse(
                "SIM  \n\"ReportService.java:142 — o método `somarSaldo` é o único ponto que agrega"
                        + " lançamentos.\"  \n\nSIM  \n\"ReportResponse.java:31 — expor o novo campo"
                        + " `saldoEstornado`.\"  \n\nSIM  \n\"ReportCache.java:88 — invalidar a"
                        + " entrada quando houver estorno.\"");

        assertThat(answer.presence()).isEqualTo(Presence.PRESENT);
        assertThat(answer.evidence()).isEqualTo("ReportService.java:142 — o método `somarSaldo` é"
                + " o único ponto que agrega lançamentos.");
    }

    /**
     * O modo de falha MEDIDO do modelo thinking: o raciocínio come o orçamento e sobra prosa sem
     * veredito nenhum. Tem de virar INDETERMINADO — se virasse AUSENTE, o validador inventaria um
     * buraco factual que o arquiteto não tem como refutar.
     */
    @Test
    void prosaSemVereditoViraIndeterminadoNuncaAusente() {
        PlanAnswerParser.Answer answer = PlanAnswerParser.parse(
                "Okay, the user is asking whether the plan mentions tests. Let me look at the");

        assertThat(answer.presence()).isEqualTo(Presence.INDETERMINATE);
    }

    /** O eco do próprio template ("PRESENTE|AUSENTE") não decide nada — foi medido no qwen3:0.6b. */
    @Test
    void ecoDoTemplateComOsDoisRotulosViraIndeterminado() {
        assertThat(PlanAnswerParser.parse("VEREDITO: SIM|NÃO").presence())
                .isEqualTo(Presence.INDETERMINATE);
    }
}
