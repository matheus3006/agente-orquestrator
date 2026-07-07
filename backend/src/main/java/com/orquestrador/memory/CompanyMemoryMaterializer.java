package com.orquestrador.memory;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Materializa a CARTA corrente no workspace como {@code <workDir>/.claude/CLAUDE.md} a cada Run
 * de turno do CEO (ADR-0018 — espelha o {@code SkillMaterializer}). IDEMPOTENTE: reescreve o
 * arquivo a cada Run; sem carta consolidada, REMOVE o arquivo (nunca serve carta velha de outra
 * versão do workspace). Só toca o CLAUDE.md — as skills do agente vivem ao lado em
 * {@code .claude/skills/} e são de outro materializer.
 */
@Component
public class CompanyMemoryMaterializer {

    /**
     * O arquivo do contrato. Em run de TURNO o executor o escreve no workspace do Run pelo
     * {@code ExecutionTarget.writeWorkspaceFiles} (replace-ou-remove espelhando este
     * materializer); em run de Issue o escritor NAO o toca.
     */
    public static final String CLAUDE_MD = ".claude/CLAUDE.md";

    private final CompanyMemoryRepository memories;

    public CompanyMemoryMaterializer(CompanyMemoryRepository memories) {
        this.memories = memories;
    }

    public void materialize(Path workDir, UUID companyId) {
        Path claudeMd = workDir.resolve(CLAUDE_MD);
        Optional<CompanyMemory> carta =
                memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA);
        try {
            if (carta.isEmpty()) {
                Files.deleteIfExists(claudeMd);
                return;
            }
            Files.createDirectories(claudeMd.getParent());
            Files.writeString(claudeMd, carta.get().getBody(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar a carta em " + claudeMd, exception);
        }
    }
}
