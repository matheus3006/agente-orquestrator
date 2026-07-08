package com.orquestrador.execution.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Gramática do CLI `claude` (ADR-0014): traduz o motor (prompt + model + effort) no argv que vai DIRETO
 * ao ProcessBuilder — sem shell, então o prompt é UM elemento (nada de quoting/injeção). Módulo profundo
 * atrás de um seam mínimo (regra de ouro ADR-0010): as três regras chatas do esforço moram só aqui.
 *
 * <ul>
 *   <li>{@code model} não-branco → {@code --model <model>}; branco/null → omite (default do plano).</li>
 *   <li>{@code effort} nos níveis {@code low|medium|high|xhigh|max} → {@code --effort <effort>}.</li>
 *   <li>{@code effort="ultracode"} → {@code --settings {"ultracode":true}} (JSON CRU, sem aspas de shell)
 *       — ultracode NÃO é effort, é setting session-only que manda xhigh + orquestra workflows.</li>
 *   <li>{@code effort} branco/null → omite.</li>
 * </ul>
 */
final class ClaudeArgv {

    private static final String ULTRACODE = "ultracode";
    private static final String ULTRACODE_SETTINGS = "{\"ultracode\":true}";

    private ClaudeArgv() {
    }

    /** Run normal: sem religar sessão (o caso comum — D4, --resume desligado para continuar). */
    static List<String> build(String prompt, String model, String effort) {
        return build(prompt, model, effort, null);
    }

    static List<String> build(String prompt, String model, String effort, String priorSession) {
        List<String> argv = new ArrayList<>();
        argv.add("claude");
        argv.add("-p");
        argv.add(prompt);
        argv.add("--output-format");
        argv.add("stream-json");
        argv.add("--verbose");
        // Run headless (-p) nao tem quem aprovar permissao interativa — sem isto o agente nem
        // ESCREVE no proprio /workspace (provado no smoke F.9d: 3 Writes bloqueados). A fronteira
        // de seguranca e o CONTAINER (F.8/ADR-0016), nao o prompt do CLI.
        argv.add("--permission-mode");
        argv.add("bypassPermissions");
        // ADR-0030/M9: SÓ a rede resume-cirúrgico do handoff preenche priorSession (todo run normal
        // passa null — D4, --resume desligado para CONTINUAR). Religa a MESMA sessão só p/ destilar.
        if (isPresent(priorSession)) {
            argv.add("--resume");
            argv.add(priorSession.trim());
        }
        if (isPresent(model)) {
            argv.add("--model");
            argv.add(model.trim());
        }
        appendEffort(argv, effort);
        return List.copyOf(argv);
    }

    private static void appendEffort(List<String> argv, String effort) {
        if (!isPresent(effort)) {
            return; // default do plano
        }
        String value = effort.trim();
        if (value.equals(ULTRACODE)) {
            argv.add("--settings");
            argv.add(ULTRACODE_SETTINGS);
        } else {
            argv.add("--effort");
            argv.add(value);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
