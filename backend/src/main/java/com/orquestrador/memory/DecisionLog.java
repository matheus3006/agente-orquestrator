package com.orquestrador.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * O Decision-log (ADR-0019): a escrita única das Decisões (o gate é a FÁBRICA, D5) + o "Log de
 * Decisões filtrável" (data/área/tipo). Invariante de PRESENÇA (D6): não existe Decisão sem
 * racional — o grill acontece no gate (ADR-0020); aqui é o backstop que nenhum caller pode pular.
 *
 * <p>O filtro roda em memória sobre o log da company (ordenado mais-recente-primeiro): Decisões
 * têm o volume de GATES, não de runs — se um dia virar fleet, o índice
 * {@code idx_decision_company_*} já está no schema para empurrar o filtro pro banco.
 */
@Service
public class DecisionLog {

    private final DecisionRepository decisions;

    public DecisionLog(DecisionRepository decisions) {
        this.decisions = decisions;
    }

    @Transactional
    public Decision record(Decision decision) {
        if (decision.getRacional() == null || decision.getRacional().isBlank()) {
            throw new IllegalArgumentException(
                    "Decisao sem racional nao existe (ADR-0019/D6 — regime de presenca)");
        }
        return decisions.save(decision);
    }

    @Transactional(readOnly = true)
    public List<Decision> query(UUID companyId, String area, DecisionTipo tipo, Instant from, Instant to) {
        return decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(decision -> area == null || decision.getArea().equals(area))
                .filter(decision -> tipo == null || decision.getTipo() == tipo)
                .filter(decision -> from == null || !decision.getDecidedAt().isBefore(from))
                .filter(decision -> to == null || !decision.getDecidedAt().isAfter(to))
                .toList();
    }

    /**
     * A taxonomia do TIPO derivada da área (ADR-0019/D4 + D9 do ADR-0020 — derivação, não
     * julgamento): área de direção/estratégia → DIRECAO (o que a carta projeta); técnica →
     * TECNICA; o resto do dia-a-dia dos gates → OPERACIONAL.
     */
    public static DecisionTipo tipoFor(String area) {
        String normalized = Normalizer.normalize((area == null ? "" : area).toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (normalized.contains("direcao") || normalized.contains("estrategia")
                || normalized.contains("roadmap") || normalized.contains("visao")) {
            return DecisionTipo.DIRECAO;
        }
        if (normalized.contains("tecnica") || normalized.contains("arquitetura")
                || normalized.contains("stack")) {
            return DecisionTipo.TECNICA;
        }
        return DecisionTipo.OPERACIONAL;
    }
}
