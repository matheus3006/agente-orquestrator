package com.orquestrador.org.engine;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F.7 fatia E (ADR-0014): serve o catálogo curado de motores para a UI (dropdown "Motor" — hire + detail).
 * Board-only (o operador é quem configura o motor dos agentes). O catálogo é GLOBAL; a rota é company-scoped
 * só para gatear acesso pelo {@link CompanyAccessGuard}. Só {@code enabled=true}; o {@code label} preenche
 * os 3 eixos por baixo. Sugere, não restringe (ADR-0014/Q2).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/engines")
public class EngineCatalogController {

    private final EngineCatalogRepository catalog;
    private final CompanyAccessGuard guard;

    public EngineCatalogController(EngineCatalogRepository catalog, CompanyAccessGuard guard) {
        this.catalog = catalog;
        this.guard = guard;
    }

    @GetMapping
    public List<EngineResponse> list(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        return catalog.findByEnabledTrueOrderByLabelAsc().stream()
                .map(EngineResponse::from)
                .toList();
    }

    /** O que a UI consome: o trio do motor + a janela de contexto + o label legível. */
    public record EngineResponse(String adapterType, String model, String effort,
                                 Integer contextWindow, String label) {
        static EngineResponse from(EngineCatalog engine) {
            return new EngineResponse(engine.getAdapterType(), engine.getModel(), engine.getEffort(),
                    engine.getContextWindow(), engine.getLabel());
        }
    }
}
