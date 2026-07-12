package com.orquestrador.execution.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N5 (ADR-0041, D5): side-channel do Codex — o stream do parent não traz o usage do subagente
 * (laudo, Sonda 1), só o {@code thread_id} (via {@link CodexStreamParser#childThreadIds()}). Este
 * leitor resolve o rollout de cada filho em {@code ~/.codex/sessions/**} e soma o {@code
 * token_count} (cumulativo, last-wins) do último evento do rollout. Tolerante: rollout ausente ⟹
 * WARN, nunca lança — o run nunca quebra por causa da contabilidade off-book.
 */
class CodexSubagentUsageReaderTest {

    @Test
    void somaOTokenCountDoUltimoEventoDoRollout(@TempDir Path sessionsRoot) throws IOException {
        Path dayDir = sessionsRoot.resolve("2026/07/12");
        Files.createDirectories(dayDir);
        String threadId = "019f54ae-b076-78c3-9725-6f18cbaad8e1";
        Files.writeString(dayDir.resolve("rollout-2026-07-12T01-56-02-" + threadId + ".jsonl"),
                String.join("\n",
                        "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + threadId + "\"}}",
                        "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_started\"}}",
                        "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                                + "\"total_token_usage\":{\"input_tokens\":17595,\"cached_input_tokens\":4992,"
                                + "\"output_tokens\":20}}}}",
                        "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\"}}"));

        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(sessionsRoot);
        CodexSubagentUsageReader.Usage usage = reader.read(Set.of(threadId));

        // input = input_tokens + cached_input_tokens (mesma convenção do CodexStreamParser principal)
        assertThat(usage.tokensInput()).isEqualTo(22587);
        assertThat(usage.tokensOutput()).isEqualTo(20);
    }

    @Test
    void somaVariosFilhosDeDiretoriosDeDiaDiferentes(@TempDir Path sessionsRoot) throws IOException {
        writeRollout(sessionsRoot, "2026/07/11", "th-red", 13672, 0);
        writeRollout(sessionsRoot, "2026/07/12", "th-blue", 13675, 0);

        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(sessionsRoot);
        CodexSubagentUsageReader.Usage usage = reader.read(Set.of("th-red", "th-blue"));

        assertThat(usage.tokensInput()).isEqualTo(27347);
    }

    @Test
    void ultimoTokenCountVenceSobreOsAnteriores(@TempDir Path sessionsRoot) throws IOException {
        // last-wins (mesma convenção do CodexStreamParser): um subagente com mais de um turno tem
        // vários token_count no rollout — cada um já é o TOTAL cumulativo do CLI, então o último
        // vence (não soma turno a turno, que dobraria a conta).
        Path dayDir = sessionsRoot.resolve("2026/07/12");
        Files.createDirectories(dayDir);
        Files.writeString(dayDir.resolve("rollout-x-th-multi.jsonl"), String.join("\n",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                        + "\"total_token_usage\":{\"input_tokens\":100,\"cached_input_tokens\":0,"
                        + "\"output_tokens\":10}}}}",
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                        + "\"total_token_usage\":{\"input_tokens\":250,\"cached_input_tokens\":0,"
                        + "\"output_tokens\":30}}}}"));

        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(sessionsRoot);
        CodexSubagentUsageReader.Usage usage = reader.read(Set.of("th-multi"));

        assertThat(usage.tokensInput()).isEqualTo(250);
        assertThat(usage.tokensOutput()).isEqualTo(30);
    }

    @Test
    void rolloutAusenteNaoLancaESomaZeroParaAqueleFilho(@TempDir Path sessionsRoot) throws IOException {
        writeRollout(sessionsRoot, "2026/07/12", "th-existe", 100, 10);

        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(sessionsRoot);
        CodexSubagentUsageReader.Usage usage = reader.read(Set.of("th-existe", "th-sumido"));

        assertThat(usage.tokensInput()).isEqualTo(100);
        assertThat(usage.tokensOutput()).isEqualTo(10);
    }

    @Test
    void diretorioDeSessoesInexistenteNaoLanca() {
        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(Path.of("/tmp/nao-existe-jamais-xyz"));

        CodexSubagentUsageReader.Usage usage = reader.read(Set.of("qualquer"));

        assertThat(usage.tokensInput()).isZero();
        assertThat(usage.tokensOutput()).isZero();
    }

    @Test
    void semChildThreadIdsNaoAndaNoDisco() {
        CodexSubagentUsageReader reader = new CodexSubagentUsageReader(Path.of("/tmp/irrelevante"));

        CodexSubagentUsageReader.Usage usage = reader.read(Set.of());

        assertThat(usage.tokensInput()).isZero();
        assertThat(usage.tokensOutput()).isZero();
    }

    private static void writeRollout(Path sessionsRoot, String dayPath, String threadId,
                                     int inputTokens, int outputTokens) throws IOException {
        Path dayDir = sessionsRoot.resolve(dayPath);
        Files.createDirectories(dayDir);
        Files.writeString(dayDir.resolve("rollout-x-" + threadId + ".jsonl"),
                "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                        + "\"total_token_usage\":{\"input_tokens\":" + inputTokens + ",\"cached_input_tokens\":0,"
                        + "\"output_tokens\":" + outputTokens + "}}}}");
    }
}
