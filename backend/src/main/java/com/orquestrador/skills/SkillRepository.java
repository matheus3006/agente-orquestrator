package com.orquestrador.skills;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    /** Identidade global (Camada 1): a skill_key é única em todo o catálogo do KnowAll. */
    Optional<Skill> findBySkillKey(String skillKey);

    /**
     * Busca do KnowAll no H2 (Fatia 7b, impl {@link SubstringSkillSearch}): substring case-insensitive
     * sobre os METADADOS ({@code name}/{@code description}/{@code triggers}) — NUNCA o body. Ordem
     * determinística por {@code skill_key} (o CONTRATO testado; ranking fica com o pg_trgm no Postgres).
     * O {@code limit} vem via {@link Pageable} ({@code PageRequest.of(0, limit)}).
     */
    @Query("select skill from Skill skill "
            + "where lower(skill.name) like lower(concat('%', :query, '%')) "
            + "or lower(skill.description) like lower(concat('%', :query, '%')) "
            + "or lower(skill.triggers) like lower(concat('%', :query, '%')) "
            + "order by skill.skillKey asc")
    List<Skill> searchMetadataSubstring(@Param("query") String query, Pageable pageLimit);

    /**
     * Busca do KnowAll no Postgres (Fatia 7b, impl {@link PgTrgmSkillSearch}): filtra por substring com
     * {@code ILIKE} (acelerado pelo GIN {@code idx_skill_trgm} da V7) sobre os METADADOS e RANQUEIA por
     * similaridade trigram (pg_trgm), com {@code skill_key} como desempate determinístico. Nunca o body.
     */
    // WHERE sem coalesce (ILIKE sobre NULL já resulta em não-match) → o GIN idx_skill_trgm serve as TRÊS
    // colunas. O coalesce fica só no ORDER BY, onde importa: similarity(NULL,..)=NULL viraria ordem instável.
    @Query(value = "select * from skill "
            + "where name ilike ('%' || :query || '%') "
            + "or description ilike ('%' || :query || '%') "
            + "or triggers ilike ('%' || :query || '%') "
            + "order by greatest(similarity(coalesce(name, ''), :query), "
            + "similarity(coalesce(description, ''), :query), similarity(coalesce(triggers, ''), :query)) desc, "
            + "skill_key asc limit :limit", nativeQuery = true)
    List<Skill> searchMetadataTrigram(@Param("query") String query, @Param("limit") int limit);
}
