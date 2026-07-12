package com.orquestrador.execution.adapter;

import com.orquestrador.execution.adapter.ClaudeStreamParser.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O parser do stream-json do `claude` (ADR-0014, módulo profundo atrás de seam pequeno — regra de ouro
 * ADR-0010, testado com fixture porque o CLI real não roda no teste): cada linha JSON vira um Step
 * semântico (type = evento nativo, detalhe legível), acumula session_id/model/usage; linha não-JSON
 * degrada para STDOUT (fail-soft).
 */
class ClaudeStreamParserTest {

    private static final List<String> STREAM = List.of(
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-abc\",\"model\":\"claude-opus-4-8\"}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Vou ler o arquivo.\"}],"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}},\"session_id\":\"sess-abc\"}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\"}]}}",
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}",
            "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"Pronto.\",\"session_id\":\"sess-abc\","
                    + "\"usage\":{\"input_tokens\":120,\"cache_read_input_tokens\":3000,"
                    + "\"cache_creation_input_tokens\":0,\"output_tokens\":45}}");

    @Test
    void traduzCadaLinhaEmStepSemantico() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        List<Step> steps = STREAM.stream().map(parser::accept).toList();
        assertThat(steps).extracting(Step::type)
                .containsExactly("SYSTEM", "ASSISTANT", "ASSISTANT", "USER", "RESULT");
        assertThat(steps.get(0).detail()).isEqualTo("init");
        assertThat(steps.get(1).detail()).isEqualTo("Vou ler o arquivo.");
        assertThat(steps.get(2).detail()).isEqualTo("→ Read");
        assertThat(steps.get(4).detail()).isEqualTo("Pronto.");
    }

    @Test
    void extraiSessionModelETokensDoStream() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        STREAM.forEach(parser::accept);
        assertThat(parser.session()).isEqualTo("sess-abc");
        assertThat(parser.model()).isEqualTo("claude-opus-4-8");
        assertThat(parser.sawUsage()).isTrue();
        // input = input_tokens + cache_read + cache_creation (o contexto REAL — insumo do handoff 50-60%)
        assertThat(parser.tokensInput()).isEqualTo(3120);
        assertThat(parser.tokensOutput()).isEqualTo(45);
    }

    @Test
    void linhaNaoJsonViraStdoutFailSoft() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        Step step = parser.accept("warning: rate limit approaching");
        assertThat(step.type()).isEqualTo("STDOUT");
        assertThat(step.detail()).isEqualTo("warning: rate limit approaching");
        assertThat(parser.sawUsage()).isFalse();
    }

    @Test
    void semUsageNaoMarcaTokens() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"init\"}");
        assertThat(parser.sawUsage()).isFalse();
        assertThat(parser.tokensInput()).isZero();
        assertThat(parser.tokensOutput()).isZero();
    }

    @Test
    void somaOUsageDeCadaSubagenteDoTaskNotification() {
        // F-N5 (ADR-0041, D5): o usage do result NÃO inclui subagentes (laudo, Sonda 1) — mas cada
        // um, ao completar, emite task_notification.usage.total_tokens no stream JÁ hoje (sem flag
        // nova). Dois subagentes (RED+BLUE) somam; o usage do turno principal fica separado.
        ClaudeStreamParser parser = new ClaudeStreamParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"task_notification\",\"usage\":{\"total_tokens\":13672}}");
        parser.accept("{\"type\":\"system\",\"subtype\":\"task_notification\",\"usage\":{\"total_tokens\":13675}}");

        assertThat(parser.subagentTokens()).isEqualTo(27347);
    }

    @Test
    void semTaskNotificationSubagentTokensFicaZero() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        STREAM.forEach(parser::accept);
        assertThat(parser.subagentTokens()).isZero();
    }

    @Test
    void farejaFalhaDeCredencialSoForaDaGramaticaJson() {
        // Peça 4 do ADR-0016: o aviso de auth do CLI vem em texto puro → fareja. A PROSA do
        // modelo mencionando login vive DENTRO do JSON → jamais fareja (anti-falso-positivo:
        // um agente que trabalha numa tela de login não pode "cheirar" a credencial quebrada).
        ClaudeStreamParser parser = new ClaudeStreamParser();

        parser.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"Implementei a tela de login com OAuth e API key.\"}]}}");
        assertThat(parser.credentialFailure()).isNull();

        parser.accept("Invalid API key · Please run /login");
        assertThat(parser.credentialFailure()).isEqualTo("Invalid API key · Please run /login");
    }

    @Test
    void linhaNaoJsonSemCaraDeAuthNaoFareja() {
        ClaudeStreamParser parser = new ClaudeStreamParser();
        parser.accept("baixando dependencias do projeto...");
        assertThat(parser.credentialFailure()).isNull();
    }
}
