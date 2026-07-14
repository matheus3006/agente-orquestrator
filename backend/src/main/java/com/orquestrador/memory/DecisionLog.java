package com.orquestrador.memory;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.identity.company.CompanyRepository;
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
 * O endereço humano {@code #N} (C4, clone da ADR-0033) nasce AQUI, no chokepoint único: MAX+1
 * serializado no lock da linha da company — denso, imutável, nunca reusado; a unique é backstop.
 *
 * <p>O filtro roda em memória sobre o log da company (ordenado mais-recente-primeiro): Decisões
 * têm o volume de GATES, não de runs — se um dia virar fleet, o índice
 * {@code idx_decision_company_*} já está no schema para empurrar o filtro pro banco.
 */
@Service
public class DecisionLog {

    private final DecisionRepository decisions;
    private final CompanyRepository companies;

    public DecisionLog(DecisionRepository decisions, CompanyRepository companies) {
        this.decisions = decisions;
        this.companies = companies;
    }

    @Transactional
    public Decision record(Decision decision) {
        if (decision.getRacional() == null || decision.getRacional().isBlank()) {
            throw new IllegalArgumentException(
                    "Decisao sem racional nao existe (ADR-0019/D6 — regime de presenca)");
        }
        companies.findByIdForUpdate(decision.getCompanyId())
                .orElseThrow(() -> new NotFoundException(
                        "Company nao encontrada: " + decision.getCompanyId()));
        return decisions.save(decision.enderecada(decisions.maxSeqByCompany(decision.getCompanyId()) + 1));
    }

    /**
     * Resolve o endereço humano {@code #N} (C4) pro id de máquina — a perna de escrita do D11:
     * o CEO cita "#7", o dono sela, o link entra como UUID. {@code #N} inexistente NESTA company
     * → 409 acionável (endereço citado errado se conserta re-emitindo, nunca meio-commitando).
     */
    @Transactional(readOnly = true)
    public UUID idDoEndereco(UUID companyId, int seq) {
        return decisions.findByCompanyIdAndSeq(companyId, seq)
                .map(Decision::getId)
                .orElseThrow(() -> new ConflictException("Decisao #" + seq
                        + " nao existe nesta company — confira o endereco citado"));
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
