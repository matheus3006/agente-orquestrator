package com.orquestrador.memory;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Materializa a DIETA sempre-on da company no workspace como {@code <workDir>/<memoryFile>} (ADR-0018
 * — espelha o {@code SkillMaterializer}). O arquivo vem da {@code WorkspaceConvention} do adapter
 * (ADR-0032): {@code .claude/CLAUDE.md} no Claude Code, {@code AGENTS.md} na raiz para o codex —
 * corpo VERBATIM das projeções nos dois. IDEMPOTENTE: reescreve a cada Run; dieta vazia → REMOVE o
 * arquivo (nunca serve memória velha de outra versão do workspace). Só toca o arquivo da memória —
 * as skills do agente vivem ao lado (no {@code skillsSubdir}) e são de outro materializer.
 *
 * <p><b>F-C3 (C9) — o SPLIT do always-on:</b> a dieta é escolhida pela {@link MemoryAudience}, e as
 * DUAS pontas agora materializam. Até aqui só o turno do CEO chamava este escritor ("em run de Issue
 * o executor não o chama"): o executor rodava sem memória nenhuma, e o D12 ("os agentes APLICAM") era
 * letra morta. Agora o executor recebe os PRINCÍPIOS (o índice enxuto, com os {@code #N} que abrem o
 * {@code memory_fetch}) — e nunca a carta.
 */
@Component
public class CompanyMemoryMaterializer {

    private final CompanyMemoryRepository memories;

    public CompanyMemoryMaterializer(CompanyMemoryRepository memories) {
        this.memories = memories;
    }

    public void materialize(Path workDir, String memoryFile, UUID companyId, MemoryAudience audience) {
        Path alvo = workDir.resolve(memoryFile);
        String dieta = dieta(companyId, audience);
        try {
            if (dieta.isEmpty()) {
                Files.deleteIfExists(alvo);
                return;
            }
            Files.createDirectories(alvo.getParent());
            Files.writeString(alvo, dieta, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar a memória em " + alvo, exception);
        }
    }

    /**
     * As projeções CORRENTES da dieta desta ponta, concatenadas na ordem de leitura — cada uma
     * VERBATIM (o corpo é a projeção determinística do consolidator; nada é reescrito aqui).
     * Projeção ausente é simplesmente pulada; nenhuma presente ⟹ vazio ⟹ o arquivo some.
     */
    private String dieta(UUID companyId, MemoryAudience audience) {
        List<String> superficies = new ArrayList<>();
        for (MemoryKind kind : audience.dieta()) {
            memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, kind)
                    .map(CompanyMemory::getBody)
                    .filter(body -> !body.isBlank())
                    .ifPresent(superficies::add);
        }
        return String.join("\n\n", superficies);
    }
}
