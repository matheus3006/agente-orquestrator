package com.orquestrador.skills;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * KnowAll nível-operador (ADR-0008): a descoberta é GLOBAL (busca o catálogo, Camada 1), mas cada
 * resultado é ANOTADO com o estado de install relativo à company do chamador — "já instalada" vs
 * "existe no catálogo, precisa instalar". Read-only nesta fatia (7b); o install-on-attach é 7c.
 */
@Service
public class KnowAllService {

    private final SkillSearch skillSearch;
    private final CompanySkillRepository companySkills;

    public KnowAllService(SkillSearch skillSearch, CompanySkillRepository companySkills) {
        this.skillSearch = skillSearch;
        this.companySkills = companySkills;
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(UUID companyId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<SkillSearch.GlobalSkillHit> hits = skillSearch.search(query.strip(), limit);
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<UUID> installedSkillIds = Set.copyOf(companySkills.findInstalledSkillIds(companyId,
                hits.stream().map(SkillSearch.GlobalSkillHit::skillId).toList()));
        return hits.stream()
                .map(hit -> new SearchResult(hit.skillId(), hit.skillKey(), hit.name(), hit.description(),
                        hit.triggers(),
                        installedSkillIds.contains(hit.skillId()) ? InstallState.INSTALLED : InstallState.AVAILABLE))
                .toList();
    }

    /**
     * Casa conhecimento→skill (T.1.b, ADR-0017/A.3): dado o conjunto de conhecimentos requeridos por um
     * molde, casa CADA conhecimento com o {@code skill_key} do catálogo GLOBAL (top-1 pelo mesmo seam do
     * {@link #search}, anotado com o estado de install da company) e SINALIZA os gaps (conhecimento sem
     * skill no catálogo — a mão soberana de importar fecha o loop). Read-only.
     *
     * <p>Serve a autoria de molde: {@code matchedSkillKeys} vira o skillset do preset; {@code gaps} vira o
     * aviso ao dono. Conhecimentos em branco/nulos são pulados (nem match nem gap). A ordem de entrada é
     * preservada; {@code matchedSkillKeys} é distinto (dois conhecimentos que casam a mesma skill = 1 key).
     */
    @Transactional(readOnly = true)
    public KnowledgeMatchResult matchKnowledge(UUID companyId, List<String> knowledge) {
        if (knowledge == null || knowledge.isEmpty()) {
            return new KnowledgeMatchResult(List.of(), List.of(), List.of());
        }
        // Top-1 por conhecimento (o seam decide o ranking: pg_trgm no Postgres, substring no H2). Guarda o
        // termo ORIGINAL (o que o autor digitou) + o hit (null = gap); brancos pulados.
        List<TermHit> termHits = new ArrayList<>();
        for (String term : knowledge) {
            if (term == null || term.isBlank()) {
                continue;
            }
            List<SkillSearch.GlobalSkillHit> hits = skillSearch.search(term.strip(), 1);
            termHits.add(new TermHit(term, hits.isEmpty() ? null : hits.get(0)));
        }

        // Anota o estado de install de TODAS as skills casadas numa query só (deny-by-default por company).
        List<UUID> matchedIds = termHits.stream().map(TermHit::hit).filter(Objects::nonNull)
                .map(SkillSearch.GlobalSkillHit::skillId).distinct().toList();
        Set<UUID> installed = matchedIds.isEmpty() ? Set.of()
                : Set.copyOf(companySkills.findInstalledSkillIds(companyId, matchedIds));

        List<KnowledgeMatch> matches = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        LinkedHashSet<String> matchedSkillKeys = new LinkedHashSet<>();
        for (TermHit termHit : termHits) {
            SkillSearch.GlobalSkillHit hit = termHit.hit();
            if (hit == null) {
                matches.add(new KnowledgeMatch(termHit.knowledge(), false, null, null, null, null));
                gaps.add(termHit.knowledge());
            } else {
                InstallState state = installed.contains(hit.skillId()) ? InstallState.INSTALLED : InstallState.AVAILABLE;
                matches.add(new KnowledgeMatch(termHit.knowledge(), true, hit.skillId(), hit.skillKey(), hit.name(), state));
                matchedSkillKeys.add(hit.skillKey());
            }
        }
        return new KnowledgeMatchResult(List.copyOf(matches), List.copyOf(gaps), List.copyOf(matchedSkillKeys));
    }

    public enum InstallState {
        /** Já instalada na biblioteca da company (Camada 2). */
        INSTALLED,
        /** Existe no catálogo global, mas ainda não instalada nesta company (precisa instalar — 7c). */
        AVAILABLE
    }

    public record SearchResult(UUID skillId, String skillKey, String name, String description,
                               String triggers, InstallState installState) {
    }

    /** Um conhecimento requerido e seu top-1 no catálogo (ou {@code matched=false} = gap). */
    public record KnowledgeMatch(String knowledge, boolean matched, UUID skillId, String skillKey,
                                 String name, InstallState installState) {
    }

    /** O casamento de um conjunto de conhecimentos: detalhe por-conhecimento + gaps + o skillset pronto. */
    public record KnowledgeMatchResult(List<KnowledgeMatch> matches, List<String> gaps,
                                       List<String> matchedSkillKeys) {
    }

    /** Elo interno conhecimento→hit (hit nulo = gap); mantém o termo original para o resultado. */
    private record TermHit(String knowledge, SkillSearch.GlobalSkillHit hit) {
    }
}
