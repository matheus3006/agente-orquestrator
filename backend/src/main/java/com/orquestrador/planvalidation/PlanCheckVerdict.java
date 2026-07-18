package com.orquestrador.planvalidation;

/**
 * O registro PRÓPRIO do Uso B (docs §2, "Contrato próprio"): {@code [{checkId, present, evidence}]}.
 * Deliberadamente NÃO reusa o {@code ContextAssessment} do inspetor de contexto — aquele é um linter
 * de fontes (severidade/categoria/ação sobre {@code ContextSource}), vocabulário que não responde
 * "cumpriu a checagem X".
 *
 * @param checkId  a chave ESTÁVEL que a B-i persistiu no jsonb do molde (ex.: {@code seam},
 *                 {@code file-line}, {@code test}) — é por ela que o checklist endereça a checagem.
 * @param presence o veredito factual do modelo local.
 * @param evidence o trecho do plano que embasa o veredito (vazio quando indeterminado).
 */
public record PlanCheckVerdict(String checkId, Presence presence, String evidence) {

    /** Só o buraco factual vira item de checklist para o arquiteto — o resto é telemetria. */
    public boolean isHole() {
        return presence == Presence.ABSENT;
    }
}
