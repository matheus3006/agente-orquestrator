package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget.IsolationPosture;

import java.util.ArrayList;
import java.util.List;

/**
 * Gramática do CLI `codex` (ADR-0014): traduz o motor no argv que vai DIRETO ao ProcessBuilder (sem shell —
 * o prompt é UM elemento). Módulo profundo atrás de um seam mínimo (regra de ouro ADR-0010), irmão do
 * {@link ClaudeArgv}. Diferenças do `claude`: o esforço vai num par {@code -c model_reasoning_effort=<effort>}
 * (sem flag dedicada, sem caso ultracode) e o prompt é POSICIONAL no fim.
 *
 * <p>Dono ÚNICO dos flags do codex (F.11/ADR-0025) — a postura de isolamento do alvo vira gramática aqui,
 * flags confirmados empiricamente contra o codex 0.142.5:
 *
 * <ul>
 *   <li>{@code model} não-branco → {@code --model <model>}; branco/null → omite (default do plano).</li>
 *   <li>{@code effort} não-branco → {@code -c model_reasoning_effort=<effort>}; branco/null → omite.</li>
 *   <li>Sempre {@code --skip-git-repo-check}: o workspace do Run nunca é repo git (E1).</li>
 *   <li>{@code EXTERNALLY_SANDBOXED} → {@code --dangerously-bypass-approvals-and-sandbox} (o container É o
 *       sandbox, ADR-0023); {@code HOST} → {@code -s workspace-write} (self-sandbox; bypass no host seria
 *       exec arbitrário do modelo — recusado).</li>
 *   <li>{@code maxThreads}/{@code maxDepth} &gt; 0 → o teto NATIVO de subagentes do codex,
 *       {@code -c agents.max_threads=<n>}/{@code -c agents.max_depth=<m>} (D6/ADR-0038 — a fatia
 *       amplia autonomia e não shippa sem o teto; o eixo com 0 é omitido, nunca chave sem valor).</li>
 *   <li>{@code priorSession} presente → subcomando {@code resume <id>} depois de TODOS os flags
 *       (M9/ADR-0030 — a única rede que religa sessão; gramática pinada no source do tag).</li>
 *   <li>{@code prompt} sempre no fim (posicional — do {@code exec} ou do {@code resume}).</li>
 * </ul>
 */
final class CodexArgv {

    private CodexArgv() {
    }

    /** Run normal: sem religar sessão (o caso comum — D4, --resume desligado para continuar). */
    static List<String> build(String prompt, String model, String effort, IsolationPosture posture) {
        return build(prompt, model, effort, posture, null);
    }

    /** Sem teto (0/0): usado pelos testes de gramática pura; o adapter sempre passa o teto do D6. */
    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession) {
        return build(prompt, model, effort, posture, priorSession, 0, 0);
    }

    /** Sem schema: o caminho não-migrado (persona legada / etapa não ligada) e os testes de teto. */
    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession, int maxThreads, int maxDepth) {
        return build(prompt, model, effort, posture, priorSession, maxThreads, maxDepth, null);
    }

    /** Sem hooks: os testes de gramática pura anteriores ao F-N6; o adapter sempre passa hooksEnabled. */
    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession, int maxThreads, int maxDepth, String outputSchemaFile) {
        return build(prompt, model, effort, posture, priorSession, maxThreads, maxDepth, outputSchemaFile, false);
    }

    /** Sem MCP: o caminho pré-F-AT2 e os testes de gramática pura anteriores ao servidor do KnowAll. */
    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession, int maxThreads, int maxDepth, String outputSchemaFile,
                              boolean hooksEnabled) {
        return build(prompt, model, effort, posture, priorSession, maxThreads, maxDepth, outputSchemaFile,
                hooksEnabled, null);
    }

    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession, int maxThreads, int maxDepth, String outputSchemaFile,
                              boolean hooksEnabled, McpServerSpec mcpServer) {
        List<String> argv = new ArrayList<>();
        argv.add("codex");
        argv.add("exec");
        argv.add("--json");
        // F-N3 (ADR-0039, D2): saída estruturada nativa. Diferente do claude (schema inline), o codex
        // exige um ARQUIVO — o adapter grava o schema e passa o caminho aqui. Flag GLOBAL (funciona
        // também com o subcomando resume, laudo X-8): fica entre os flags, antes do subcomando/prompt.
        // Vazio ⟹ turno não migrado, omite. O strict do OpenAI mora no builder do schema, não aqui.
        if (isPresent(outputSchemaFile)) {
            argv.add("--output-schema");
            argv.add(outputSchemaFile.trim());
        }
        if (isPresent(model)) {
            argv.add("--model");
            argv.add(model.trim());
        }
        if (isPresent(effort)) {
            argv.add("-c");
            argv.add("model_reasoning_effort=" + effort.trim());
        }
        // D6 (ADR-0038): o teto de subagentes vira par -c nativo — agrupado com os outros -c, ANTES
        // do subcomando resume (é global). Eixo com 0 = desligado (omite; nunca `chave=0` sem sentido).
        if (maxThreads > 0) {
            argv.add("-c");
            argv.add("agents.max_threads=" + maxThreads);
        }
        if (maxDepth > 0) {
            argv.add("-c");
            argv.add("agents.max_depth=" + maxDepth);
        }
        // F-AT2 (grill agente-como-tool D2, laudo sonda MCP-consume R12-B): o servidor MCP-stdio do
        // KnowAll entra por pares -c GLOBAIS (mcp_servers.<name>.command/.args), agrupados com os
        // outros -c ANTES do subcomando resume. Sem flag de bypass de aprovação: a sonda provou que a
        // anotação readOnlyHint:true na própria tool (verdade — a leitura É read-only) já satisfaz o
        // gate próprio do codex no headless (`destructive_hint.unwrap_or(true)`, rust-v0.142.5). Nulo ⟹
        // run sem MCP (persona/etapa não-migrada), omite — o caminho pré-F-AT2.
        if (mcpServer != null && isPresent(mcpServer.command())) {
            argv.add("-c");
            argv.add("mcp_servers." + mcpServer.name() + ".command=" + tomlString(mcpServer.command()));
            argv.add("-c");
            argv.add("mcp_servers." + mcpServer.name() + ".args=" + tomlStringArray(mcpServer.args()));
        }
        argv.add("--skip-git-repo-check");
        if (posture == IsolationPosture.EXTERNALLY_SANDBOXED) {
            argv.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            argv.add("-s");
            argv.add("workspace-write");
        }
        // F-N6 (ADR-0042, D6/D7): o codex recusa rodar hooks não-confiados sem esta flag (gotcha
        // de trust, confirmado no source do tag pinado rust-v0.142.5 — `shared_options.rs`). Os
        // hooks materializam em TODO run (junto das skills, HookMaterializer), então o adapter
        // sempre passa hooksEnabled=true; só os testes de gramática pura passam false.
        if (hooksEnabled) {
            argv.add("--dangerously-bypass-hook-trust");
        }
        // ADR-0030/M9: religa a sessão só para a rede resume-cirúrgico do handoff (todo run normal
        // passa null — D4). No codex 0.142.5 resume é SUBCOMANDO (não flag) com SESSION_ID e PROMPT
        // posicionais, e `-s` não é global ⟹ flags SEMPRE antes dele — a forma do override_usage do
        // próprio source: `codex exec [OPTIONS] <COMMAND> [ARGS]` (codex-rs/exec/src/cli.rs:12 @
        // rust-v0.142.5; Command::Resume :168, posicionais :180-181/:202-203, globais :157-163).
        if (isPresent(priorSession)) {
            argv.add("resume");
            argv.add(priorSession.trim());
        }
        argv.add(prompt);
        return List.copyOf(argv);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Um valor string do config do codex (TOML via -c): aspas duplas com escape de {@code \} e {@code "}. */
    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Um array TOML de strings — {@code ["a","b"]} — para o {@code mcp_servers.<name>.args}. */
    private static String tomlStringArray(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add(tomlString(value));
        }
        return "[" + String.join(",", quoted) + "]";
    }
}
