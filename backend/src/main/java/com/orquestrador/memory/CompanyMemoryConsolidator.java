package com.orquestrador.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * O consolidador da CARTA (ADR-0019, "Pergunta 1" fechada): PROJEÇÃO DETERMINÍSTICA sobre as
 * Decisões-de-direção aprovadas pelo dono → nova versão de {@code company_memory} kind=CARTA.
 * ZERO LLM no caminho de escrita (defesa contra memory-poisoning: a carta é derivada, nunca
 * auto-escrita pelo agente). Idempotente por conteúdo: reprojetar sem Decisão nova não gera
 * versão idêntica (savelog sem ruído). Linhagem respeitada: uma Decisão SUPERSEDIDA (outra a
 * aponta em {@code supersedes}) sai da projeção — a carta é o vigente, o log é a história.
 */
@Service
public class CompanyMemoryConsolidator {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final DecisionRepository decisions;
    private final CompanyMemoryRepository memories;

    public CompanyMemoryConsolidator(DecisionRepository decisions, CompanyMemoryRepository memories) {
        this.decisions = decisions;
        this.memories = memories;
    }

    @Transactional
    public void refresh(UUID companyId) {
        List<Decision> direcoes = decisions.findByCompanyIdAndTipoOrderByDecidedAtAscIdAsc(
                companyId, DecisionTipo.DIRECAO);
        List<UUID> superseded = direcoes.stream()
                .map(Decision::getSupersedesId)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Decision> vigentes = direcoes.stream()
                .filter(decision -> !superseded.contains(decision.getId()))
                .toList();
        if (vigentes.isEmpty()) {
            return; // nada de direção ainda — carta não nasce vazia
        }
        String body = render(vigentes);
        CompanyMemory corrente = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElse(null);
        if (corrente != null && corrente.getBody().equals(body)) {
            return; // idempotente por conteúdo
        }
        int nextVersion = corrente == null ? 1 : corrente.getVersion() + 1;
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, body, nextVersion));
    }

    private static String render(List<Decision> vigentes) {
        StringBuilder out = new StringBuilder();
        out.append("# Carta de interesses da empresa\n\n");
        out.append("> DERIVADA do Decision-log (ADR-0019): projeção determinística das Decisões de\n");
        out.append("> direção aprovadas pelo dono. Não editar à mão — a fonte é o log.\n\n");
        out.append("## Direções vigentes\n");
        for (Decision decisao : vigentes) {
            out.append("- [").append(DATA.format(decisao.getDecidedAt())).append("] ")
                    .append("(").append(decisao.getArea()).append(") ")
                    .append(decisao.getOQueMudou())
                    .append(" — racional: ").append(decisao.getRacional())
                    .append("\n");
        }
        return out.toString();
    }
}
