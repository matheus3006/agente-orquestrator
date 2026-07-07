package com.orquestrador.execution.adapter;

import com.orquestrador.execution.adapter.CodexStreamParser.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parser do `codex exec --json` (ADR-0014, mesmo contrato do ClaudeStreamParser, gramática própria):
 * eventos JSONL {@code thread.started} (traz {@code thread_id} = session), {@code turn.*}, {@code item.*}
 * (o conteúdo real), {@code turn.completed} (traz {@code usage}). Não-JSON → STDOUT fail-soft.
 *
 * <p>NB: o shape do {@code usage}/session do Codex foi montado a partir do formato documentado, não de uma
 * fixture do CLI real — verificar no fechamento do backend (risco anotado no handoff D→G).
 */
class CodexStreamParserTest {

    private static final List<String> STREAM = List.of(
            "{\"type\":\"thread.started\",\"thread_id\":\"th_123\"}",
            "{\"type\":\"turn.started\"}",
            "{\"type\":\"item.completed\",\"item\":{\"item_type\":\"reasoning\",\"text\":\"Planejando.\"}}",
            "{\"type\":\"item.completed\",\"item\":{\"item_type\":\"command_execution\",\"command\":\"ls\"}}",
            "{\"type\":\"item.completed\",\"item\":{\"item_type\":\"assistant_message\",\"text\":\"Pronto.\"}}",
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200,\"cached_input_tokens\":1000,"
                    + "\"output_tokens\":50}}");

    @Test
    void traduzCadaEventoEmStepSemantico() {
        CodexStreamParser parser = new CodexStreamParser();
        List<Step> steps = STREAM.stream().map(parser::accept).toList();
        assertThat(steps).extracting(Step::type)
                .containsExactly("THREAD.STARTED", "TURN.STARTED",
                        "ITEM.COMPLETED", "ITEM.COMPLETED", "ITEM.COMPLETED", "TURN.COMPLETED");
        assertThat(steps.get(2).detail()).isEqualTo("Planejando.");
        assertThat(steps.get(3).detail()).isEqualTo("ls");
        assertThat(steps.get(4).detail()).isEqualTo("Pronto.");
    }

    @Test
    void extraiSessionETokensDoStream() {
        CodexStreamParser parser = new CodexStreamParser();
        STREAM.forEach(parser::accept);
        assertThat(parser.session()).isEqualTo("th_123");
        assertThat(parser.sawUsage()).isTrue();
        // input = input_tokens + cached_input_tokens (contexto real; denominador do handoff 50-60%)
        assertThat(parser.tokensInput()).isEqualTo(1200);
        assertThat(parser.tokensOutput()).isEqualTo(50);
    }

    @Test
    void linhaNaoJsonViraStdoutFailSoft() {
        CodexStreamParser parser = new CodexStreamParser();
        Step step = parser.accept("codex: warming up");
        assertThat(step.type()).isEqualTo("STDOUT");
        assertThat(step.detail()).isEqualTo("codex: warming up");
        assertThat(parser.sawUsage()).isFalse();
    }

    @Test
    void semUsageNaoMarcaTokens() {
        CodexStreamParser parser = new CodexStreamParser();
        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"th_1\"}");
        assertThat(parser.sawUsage()).isFalse();
        assertThat(parser.tokensInput()).isZero();
        assertThat(parser.tokensOutput()).isZero();
    }
}
