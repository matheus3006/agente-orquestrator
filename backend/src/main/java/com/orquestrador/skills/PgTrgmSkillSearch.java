package com.orquestrador.skills;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Impl da busca do KnowAll para o Postgres (Fatia 7b): filtra por substring com {@code ILIKE} (acelerado
 * pelo GIN {@code idx_skill_trgm} da V7) e RANQUEIA por similaridade trigram ({@code pg_trgm}). Ativa fora
 * do profile {@code test} (default/prod); nos testes H2 roda a {@link SubstringSkillSearch}.
 */
@Component
@Profile("!test")
public class PgTrgmSkillSearch implements SkillSearch {

    private final SkillRepository skills;

    public PgTrgmSkillSearch(SkillRepository skills) {
        this.skills = skills;
    }

    @Override
    public List<GlobalSkillHit> search(String query, int limit) {
        return skills.searchMetadataTrigram(query, limit).stream()
                .map(GlobalSkillHit::from)
                .toList();
    }
}
