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

    static List<String> build(String prompt, String model, String effort, IsolationPosture posture,
                              String priorSession) {
        List<String> argv = new ArrayList<>();
        argv.add("codex");
        argv.add("exec");
        argv.add("--json");
        if (isPresent(model)) {
            argv.add("--model");
            argv.add(model.trim());
        }
        if (isPresent(effort)) {
            argv.add("-c");
            argv.add("model_reasoning_effort=" + effort.trim());
        }
        argv.add("--skip-git-repo-check");
        if (posture == IsolationPosture.EXTERNALLY_SANDBOXED) {
            argv.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            argv.add("-s");
            argv.add("workspace-write");
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
}
