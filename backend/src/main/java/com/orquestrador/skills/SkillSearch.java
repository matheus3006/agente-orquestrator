package com.orquestrador.skills;

import java.util.List;
import java.util.UUID;

/**
 * Seam da busca do KnowAll (Fatia 7b, ADR-0008): descoberta GLOBAL sobre o catálogo (Camada 1), pelos
 * METADADOS ({@code name}/{@code description}/{@code triggers}) — NUNCA o body redigido. A variação real
 * entre ambientes vive atrás desta interface pequena: {@link PgTrgmSkillSearch} (ranking por similaridade
 * trigram, usa o GIN {@code idx_skill_trgm}) no Postgres; {@link SubstringSkillSearch} (substring
 * determinístico) no H2 dos testes. O resultado é global (sem company); a anotação de estado-de-install
 * por-company é responsabilidade do {@link KnowAllService}.
 */
public interface SkillSearch {

    /** Top-{@code limit} identidades globais cujos metadados casam com {@code query}, já ordenadas. */
    List<GlobalSkillHit> search(String query, int limit);

    /** Uma ocorrência global da busca (identidade + metadados), sem estado de install. */
    record GlobalSkillHit(UUID skillId, String skillKey, String name, String description, String triggers) {
        static GlobalSkillHit from(Skill skill) {
            return new GlobalSkillHit(skill.getId(), skill.getSkillKey(), skill.getName(),
                    skill.getDescription(), skill.getTriggers());
        }
    }
}
