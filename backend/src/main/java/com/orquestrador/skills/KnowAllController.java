package com.orquestrador.skills;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * KnowAll search via HTTP (Fatia 7b). Deny-by-default: {@code assertMember} guarda a rota. É consultável
 * por QUALQUER membro — persona do humano (board) E tool do agente (ADR-0003) — por isso não é board-only.
 * Read-only: descobre no catálogo global e anota o estado de install da company do chamador.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/knowall")
public class KnowAllController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final KnowAllService knowAll;
    private final CompanyAccessGuard guard;

    public KnowAllController(KnowAllService knowAll, CompanyAccessGuard guard) {
        this.knowAll = knowAll;
        this.guard = guard;
    }

    @GetMapping("/search")
    public List<KnowAllService.SearchResult> search(@PathVariable UUID companyId,
                                                    @RequestParam(name = "q", defaultValue = "") String query,
                                                    @RequestParam(name = "limit", defaultValue = "20") int limit) {
        guard.assertMember(companyId);
        return knowAll.search(companyId, query, clampLimit(limit));
    }

    /**
     * Casa conhecimento→skill (T.1.b): recebe os conhecimentos requeridos por um molde e devolve o
     * casamento (skill_key + install-state) + os gaps + o skillset pronto. Member-access como o /search
     * (o CEO-agente de T.3 chama como tool). Read-only — a criação do molde é o POST board-only à parte.
     */
    @PostMapping("/match")
    public KnowAllService.KnowledgeMatchResult match(@PathVariable UUID companyId,
                                                     @RequestBody MatchRequest request) {
        guard.assertMember(companyId);
        return knowAll.matchKnowledge(companyId, request == null ? null : request.knowledge());
    }

    public record MatchRequest(List<String> knowledge) {
    }

    /** Top-N sob controle: nunca menos de 1 nem mais que {@value MAX_LIMIT} (default {@value DEFAULT_LIMIT}). */
    private static int clampLimit(int requestedLimit) {
        if (requestedLimit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
