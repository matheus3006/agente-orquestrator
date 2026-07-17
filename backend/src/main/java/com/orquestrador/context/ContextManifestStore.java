package com.orquestrador.context;

/** Porta pequena de persistência do manifesto 1:1 e imutável por Run. */
@FunctionalInterface
public interface ContextManifestStore {

    void save(ContextManifest manifest);
}
