package com.orquestrador.execution.adapter;

import java.util.List;

/**
 * A convenção de workspace de um runtime (ADR-0032): ONDE o CLI deste adapter lê a carta
 * ({@code memoryFile}, raiz-relativo), as skills ({@code skillsSubdir}) e os hooks PreToolUse
 * ({@code hooksFile}/{@code hooksScript}, F-N6/ADR-0042) dentro do workspace. Irmã do
 * {@link EngineContainer}: o adapter declara DADOS; quem faz o trabalho são os
 * materializers (caminho por parâmetro) e o executor (subtrees + faxina).
 *
 * <p>Claude Code lê {@code .claude/CLAUDE.md} + {@code .claude/skills/} (layout canônico,
 * ADR-0007/0018). O codex NÃO lê {@code .claude/} — a sonda S2 (handoff G3, 2026-07-09) provou
 * os dois canais NATIVOS dele: {@code AGENTS.md} na raiz (project doc, escopo = árvore inteira)
 * e {@code <workspace>/.codex/skills/<chave>/SKILL.md} (descoberta por-projeto, mesma forma da
 * árvore {@code .claude/skills/}). Nada de skills inline no AGENTS.md: o cap
 * {@code project_doc_max_bytes} truncaria em silêncio (S1).
 *
 * <p>{@code hooksFile} é o arquivo de configuração que CADA CLI lê nativamente para hooks —
 * {@code .claude/settings.json} (bloco {@code hooks.PreToolUse}) ou {@code .codex/hooks.json}
 * (dono do arquivo inteiro). Confirmado empiricamente (F-N6): os DOIS aceitam a MESMA forma
 * {@code {"hooks":{"PreToolUse":[{"matcher":...,"hooks":[{"type":"command","command":...}]}]}}}
 * — só o arquivo/prefixo muda; o {@code HookMaterializer} escreve o MESMO script guarda em
 * {@code hooksScript} nos dois motores.
 *
 * <p>{@link #KNOWN} é a UNIÃO da faxina permanente (D6): todo Run escreve a convenção ATIVA e
 * apaga os caminhos das INATIVAS — troca de motor nunca deixa cérebro velho no chão (o caso
 * real: o {@code .claude/} desatualizado num workspace que o codex explora por busca).
 */
public record WorkspaceConvention(String memoryFile, String skillsSubdir, String hooksFile, String hooksScript) {

    /** Todas as convenções que algum adapter declara — a base da união/faxina do executor. */
    public static final List<WorkspaceConvention> KNOWN = List.of(claude(), codex());

    public static WorkspaceConvention claude() {
        return new WorkspaceConvention(".claude/CLAUDE.md", ".claude/skills",
                ".claude/settings.json", ".claude/hooks/pretooluse-guard.js");
    }

    public static WorkspaceConvention codex() {
        return new WorkspaceConvention("AGENTS.md", ".codex/skills",
                ".codex/hooks.json", ".codex/hooks/pretooluse-guard.js");
    }
}
