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
 * Materializa a CARTA corrente no workspace como {@code <workDir>/<memoryFile>} a cada Run
 * de turno do CEO (ADR-0018 — espelha o {@code SkillMaterializer}). O arquivo vem da
 * {@code WorkspaceConvention} do adapter (ADR-0032): {@code .claude/CLAUDE.md} no Claude Code,
 * {@code AGENTS.md} na raiz para o codex — corpo VERBATIM nos dois. IDEMPOTENTE: reescreve o
 * arquivo a cada Run; sem carta consolidada, REMOVE o arquivo (nunca serve carta velha de outra
 * versão do workspace). Só toca o arquivo da carta — as skills do agente vivem ao lado (no
 * {@code skillsSubdir} da convenção) e são de outro materializer. Em run de Issue o executor
 * não o chama e o escritor não toca o arquivo.
 */
@Component
public class CompanyMemoryMaterializer {

    private final CompanyMemoryRepository memories;

    public CompanyMemoryMaterializer(CompanyMemoryRepository memories) {
        this.memories = memories;
    }

    public void materialize(Path workDir, String memoryFile, UUID companyId) {
        Path claudeMd = workDir.resolve(memoryFile);
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
