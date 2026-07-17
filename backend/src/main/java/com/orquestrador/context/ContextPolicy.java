package com.orquestrador.context;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Guardas determinísticas do pacote canônico; não transforma nem resume conteúdo. */
@Component
public class ContextPolicy {

    public enum Classification {
        CANONICAL,
        DERIVED
    }

    /**
     * O writer tem semântica de replace. Publicar qualquer membro de {@code .orq} poderia apagar
     * handoff, schema ou outro estado entre Runs; por isso a recusa acontece antes de todo I/O.
     */
    public void validatePublishedPaths(List<String> relativePaths) {
        for (String relativePath : relativePaths) {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("path de contexto vazio");
            }
            Path normalized = Path.of(relativePath).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) {
                throw new IllegalArgumentException("path de contexto fora do workspace: " + relativePath);
            }
            if (normalized.getNameCount() > 0 && normalized.getName(0).toString().equals(".orq")) {
                throw new IllegalArgumentException(".orq jamais pode entrar no replace do writer");
            }
        }
    }
}
