package com.orquestrador.execution.adapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O seam da fatia AGENTS.md (ADR-0032): cada adapter DECLARA onde o seu CLI lê carta e skills
 * (molde do {@link EngineContainer} — o adapter declara DADOS, o executor e os materializers
 * fazem o trabalho). Default da interface = claude(): ClaudeCodeAdapter e ProcessAgentAdapter
 * não mudam nenhuma linha; só o CodexAdapter sobrescreve (sonda S2: o codex descobre
 * AGENTS.md na raiz e skills em .codex/skills/ NATIVAMENTE).
 */
class WorkspaceConventionTest {

    @Test
    void convencaoClaudeEOLayoutCanonicoDoClaudeCode() {
        WorkspaceConvention claude = WorkspaceConvention.claude();

        assertThat(claude.memoryFile()).isEqualTo(".claude/CLAUDE.md");
        assertThat(claude.skillsSubdir()).isEqualTo(".claude/skills");
    }

    @Test
    void convencaoCodexEONativoProvadoPelaSonda() {
        // S2 (handoff G3): AGENTS.md da raiz é lido; skills em <workspace>/.codex/skills/<chave>/
        // SKILL.md são descobertas nativamente — mesma forma da árvore .claude/skills/.
        WorkspaceConvention codex = WorkspaceConvention.codex();

        assertThat(codex.memoryFile()).isEqualTo("AGENTS.md");
        assertThat(codex.skillsSubdir()).isEqualTo(".codex/skills");
    }

    @Test
    void oMundoDasConvencoesConhecidasAlimentaAFaxina() {
        // KNOWN é a UNIÃO da faxina permanente (D6): todo Run escreve a convenção ativa e apaga
        // os caminhos das inativas — troca de motor nunca deixa cérebro velho no chão.
        assertThat(WorkspaceConvention.KNOWN)
                .containsExactly(WorkspaceConvention.claude(), WorkspaceConvention.codex());
    }

    @Test
    void adaptersClaudeEProcessHerdamODefaultClaudeDaInterface() {
        assertThat(new ClaudeCodeAdapter("").workspaceConvention())
                .isEqualTo(WorkspaceConvention.claude());
        assertThat(new ProcessAgentAdapter().workspaceConvention())
                .isEqualTo(WorkspaceConvention.claude());
    }

    @Test
    void codexAdapterDeclaraAConvencaoNativaDoCodex() {
        assertThat(new CodexAdapter().workspaceConvention())
                .isEqualTo(WorkspaceConvention.codex());
    }
}
