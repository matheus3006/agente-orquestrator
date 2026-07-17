package com.orquestrador.context.intelligence;

import com.orquestrador.context.ContextPackage;
import com.orquestrador.context.ContextManifest;
import com.orquestrador.context.MaterializedArtifact;

import java.nio.file.Path;
import java.util.List;

/** Porta local para análise derivada; nenhuma abstração do Spring AI cruza este seam. */
public interface ContextIntelligence {

    /** Agenda uma inspeção best-effort sem alterar nem bloquear o pacote que seguirá para o Run. */
    void inspect(View view);

    /**
     * Visão estrutural sem prompt/schema. O adapter ainda prova hash e classificação antes de ler
     * cada corpo, mas conteúdo vivo do Run sequer é representável neste contrato.
     */
    record View(Path workDir, List<MaterializedArtifact> artifacts, ContextManifest manifest) {

        public View {
            artifacts = List.copyOf(artifacts);
        }

        public static View from(ContextPackage contextPackage) {
            return new View(contextPackage.workDir(), contextPackage.artifacts(), contextPackage.manifest());
        }
    }
}
