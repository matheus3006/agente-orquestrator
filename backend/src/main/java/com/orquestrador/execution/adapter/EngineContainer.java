package com.orquestrador.execution.adapter;

import com.orquestrador.execution.runtime.ExecutionTarget.VolumeMount;

import java.util.List;

/**
 * O material de container de um runtime (F.8, ADR-0016): a imagem com o CLI PINADO (defesa contra
 * o {@code --bare} virar default do {@code claude -p} e exigir API key — risco do ADR-0014) + o
 * volume de credencial do PLANO montado no home do container + o remedio legivel quando a
 * credencial falta ou expira.
 *
 * <p>A montagem e do HOME INTEIRO ({@code /home/node}), nao so de {@code ~/.claude}: o
 * onboarding-state do Claude vive em {@code ~/.claude.json}, FORA do dir (deep-research 3-0), e o
 * home-como-volume carrega os dois de uma vez — o que o login de setup gravar e o que o agente le.
 * A montagem seletiva E o isolamento: cada adapter declara SO o proprio volume, e o argv do
 * {@code docker run} nunca ve o plano do outro. O executor nao conhece nada disto (ADR-0010).
 */
public record EngineContainer(String image, List<VolumeMount> credentialMounts, String credentialFailureHint) {

    /** Versoes dos CLIs pinadas nas imagens — o teste de pino as trava ao ARG de cada Dockerfile. */
    static final String CLAUDE_CODE_CLI_VERSION = "2.1.201";
    static final String CODEX_CLI_VERSION = "0.142.5";

    private static final String CONTAINER_HOME = "/home/node";

    public static EngineContainer claudeCode() {
        return new EngineContainer(
                "orq-claude-code:" + CLAUDE_CODE_CLI_VERSION,
                List.of(new VolumeMount("orq_claude_creds", CONTAINER_HOME)),
                "credencial do plano Claude ausente ou expirada no volume orq_claude_creds"
                        + " — refaca o login: infra/engines/setup-claude.sh");
    }

    public static EngineContainer codex() {
        return new EngineContainer(
                "orq-codex:" + CODEX_CLI_VERSION,
                List.of(new VolumeMount("orq_codex_creds", CONTAINER_HOME)),
                "credencial do plano Codex ausente ou expirada no volume orq_codex_creds"
                        + " — refaca o login: infra/engines/setup-codex.sh");
    }

    /** O arquetipo process: shell puro, sem plano de assinatura — nada a montar, nada a expirar. */
    public static EngineContainer plainShell() {
        return new EngineContainer("alpine:3", List.of(), null);
    }
}
