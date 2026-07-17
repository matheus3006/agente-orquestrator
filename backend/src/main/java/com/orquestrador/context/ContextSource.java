package com.orquestrador.context;

/**
 * Proveniência allowlisted de uma fonte do pacote. O endereço é opaco e nenhum campo aceita o
 * corpo da fonte, prompt, resposta de modelo ou dado livre do usuário.
 */
public record ContextSource(String type, String address, String version, String hash, long bytes,
                            ContextPolicy.Classification classification, Inclusion inclusion, String reason) {

    public enum Inclusion {
        INCLUDED,
        OMITTED
    }
}
