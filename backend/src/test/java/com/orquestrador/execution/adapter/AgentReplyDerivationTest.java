package com.orquestrador.execution.adapter;

import com.orquestrador.execution.RunFinalOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A SAÍDA FINAL de um turno (o reply que o dono vê) derivada do stream de CADA motor pela regra única do
 * {@link RunFinalOutput} (ADR-0010): {@code RESULT > ASSISTANT > STDOUT}. Exercita o caminho REAL —
 * linhas cruas do stream → parser do motor → {@link RunFinalOutput} — exatamente como o
 * {@code AdapterRunExecutor} fia ({@code steps.emit(step.type(), step.detail())}).
 *
 * <p>Regressão da ADR-0030 (VENDO live 2026-07-08): no motor codex o reply pegava o aviso cru do CLI
 * ({@code "Reading additional input from stdin..."}) em vez da mensagem final do agente, porque o codex
 * rotula a mensagem como {@code item.completed}/{@code agent_message} — fora do vocabulário do
 * {@link RunFinalOutput}. A correção mora no parser (o adapter traduz a própria gramática para o nível
 * compartilhado {@code RESULT}, last-wins — a última mensagem é a resposta); o {@link RunFinalOutput} fica intocado.
 */
class AgentReplyDerivationTest {

    private static String codexReplyFrom(List<String> stream) {
        CodexStreamParser parser = new CodexStreamParser();
        RunFinalOutput reply = new RunFinalOutput();
        for (String line : stream) {
            CodexStreamParser.Step step = parser.accept(line);
            reply.accept(step.type(), step.detail());
        }
        return reply.text();
    }

    @Test
    void codex_mensagemFinalDoAgenteVenceOAvisoCruDoStdin() {
        // Shape REAL do codex 0.142.5 (handoff-f11 §A): item.completed com item.type=agent_message + text.
        String reply = codexReplyFrom(List.of(
                "Reading additional input from stdin...", // aviso cru do CLI → STDOUT fail-soft
                "{\"type\":\"thread.started\",\"thread_id\":\"th_1\"}",
                "{\"type\":\"turn.started\"}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\","
                        + "\"text\":\"Gravei o bilhete; passo 1: contratar Lyra.\"}}",
                "{\"type\":\"turn.completed\",\"usage\":{\"output_tokens\":5}}"));

        assertThat(reply).isEqualTo("Gravei o bilhete; passo 1: contratar Lyra.");
    }

    @Test
    void codex_ultimaMensagemDoAgenteVence_asNarracoesIntermediariasNaoEntram() {
        // Cada agent_message do codex é uma mensagem COMPLETA (não um chunk como no claude); a ÚLTIMA é a
        // resposta ao dono. A narração de progresso do meio NÃO entra no reply (last-wins, nível RESULT).
        String reply = codexReplyFrom(List.of(
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Vou delegar e preparar o caminho.\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Feynman devolveu o texto; vou gravar.\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Sou Atlas, o CEO delegador.\"}}"));

        assertThat(reply).isEqualTo("Sou Atlas, o CEO delegador.");
    }

    @Test
    void codex_reasoningEComandoNaoViramReply() {
        // A armadilha: reasoning TAMBÉM carrega `text`. Só a mensagem do agente é o reply — reasoning e
        // comando ficam de fora (senão a prosa do reply viria poluída com o rascunho de raciocínio).
        String reply = codexReplyFrom(List.of(
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\",\"text\":\"Rascunho do plano.\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"command\":\"ls -la\"}}",
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Pronto, quadro criado.\"}}"));

        assertThat(reply).isEqualTo("Pronto, quadro criado.");
    }

    @Test
    void codex_shapeDocumentadoAssistantMessageTambemSobe() {
        // Back-compat: o valor documentado/fixture (item_type=assistant_message) também é reconhecido.
        String reply = codexReplyFrom(List.of(
                "{\"type\":\"item.completed\",\"item\":{\"item_type\":\"assistant_message\",\"text\":\"Feito.\"}}"));

        assertThat(reply).isEqualTo("Feito.");
    }

    @Test
    void claude_resultContinuaSendoOReply() {
        // Não-regressão (requisito explícito): o claude segue usando o RESULT como reply, vencendo a
        // prosa ASSISTANT — a correção do codex não tocou o parser do claude nem o RunFinalOutput.
        ClaudeStreamParser parser = new ClaudeStreamParser();
        RunFinalOutput reply = new RunFinalOutput();
        List<String> stream = List.of(
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"pensando...\"}]}}",
                "{\"type\":\"result\",\"result\":\"Resposta final do claude.\"}");
        for (String line : stream) {
            ClaudeStreamParser.Step step = parser.accept(line);
            reply.accept(step.type(), step.detail());
        }

        assertThat(reply.text()).isEqualTo("Resposta final do claude.");
    }
}
