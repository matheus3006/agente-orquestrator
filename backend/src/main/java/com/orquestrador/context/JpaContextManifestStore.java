package com.orquestrador.context;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Adaptador JPA append-only: uma segunda gravação para o mesmo Run é sempre erro. */
@Component
public class JpaContextManifestStore implements ContextManifestStore {

    private final RunContextManifestRepository manifests;
    private final ObjectMapper json;

    public JpaContextManifestStore(RunContextManifestRepository manifests, ObjectMapper json) {
        this.manifests = manifests;
        this.json = json;
    }

    @Override
    @Transactional
    public void save(ContextManifest manifest) {
        if (manifests.existsById(manifest.runId())) {
            throw new IllegalStateException("manifesto de contexto é imutável para o Run " + manifest.runId());
        }
        try {
            String sourcesJson = json.writeValueAsString(manifest.sources());
            manifests.saveAndFlush(new StoredContextManifest(manifest, sourcesJson));
        } catch (JacksonException ex) {
            throw new IllegalStateException("não foi possível serializar a proveniência do contexto", ex);
        }
    }
}
