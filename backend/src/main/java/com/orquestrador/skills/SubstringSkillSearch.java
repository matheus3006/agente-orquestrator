package com.orquestrador.skills;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Impl da busca do KnowAll para o H2 dos testes (Fatia 7b): substring case-insensitive sobre os
 * metadados, ordem determinística por {@code skill_key}. Garante o CONTRATO (contém/não-contém, top-N,
 * case-insensitive) sem depender de {@code pg_trgm} (que o H2 não tem). Ativa só no profile {@code test};
 * no Postgres roda a {@link PgTrgmSkillSearch}.
 */
@Component
@Profile("test")
public class SubstringSkillSearch implements SkillSearch {

    private final SkillRepository skills;

    public SubstringSkillSearch(SkillRepository skills) {
        this.skills = skills;
    }

    @Override
    public List<GlobalSkillHit> search(String query, int limit) {
        return skills.searchMetadataSubstring(query, PageRequest.of(0, limit)).stream()
                .map(GlobalSkillHit::from)
                .toList();
    }
}
