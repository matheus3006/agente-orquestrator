package com.orquestrador.memory;

import com.orquestrador.governance.approval.RacionalPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * A fábrica ÚNICA de Decisões do gate (ADR-0019/D5, extraída pela Frente C — C15): toda porta
 * (approval pré-hoc, review pós-hoc; caso B amanhã) fabrica por AQUI. Uma fábrica, nunca duas —
 * espelhar esta fiação em cada porta é o drift que produziu os seams mortos que a Frente C cura.
 *
 * <p>Moram DENTRO (nenhuma porta pode esquecer):
 * <ul>
 *   <li><b>C14 — "sem substância → sem Decisão":</b> passagem sem racional não fabrica nada
 *       (memória durável não nasce do nada; a história fica no activity_log da porta);</li>
 *   <li><b>D10 — a maiêutica:</b> INTUICAO carrega o probe como evidência, nunca como bloqueio;</li>
 *   <li><b>a projeção:</b> direção consolida a carta na MESMA tx do gate (ADR-0018/0019).</li>
 * </ul>
 * O grill de PRESENÇA (D6/D7) segue na porta — bloquear/insistir é conversa do gate com o dono;
 * aqui só se registra o que já tem substância ({@code DecisionLog.record} é o backstop).
 */
@Service
public class GateDecisionFactory {

    private final DecisionLog decisionLog;
    private final CompanyMemoryConsolidator consolidator;
    private final RacionalPolicy racionalPolicy;

    public GateDecisionFactory(DecisionLog decisionLog, CompanyMemoryConsolidator consolidator,
                               RacionalPolicy racionalPolicy) {
        this.decisionLog = decisionLog;
        this.consolidator = consolidator;
        this.racionalPolicy = racionalPolicy;
    }

    @Transactional
    public Optional<Decision> fabricate(GatePassage passage) {
        if (passage.racional() == null || passage.racional().isBlank()) {
            return Optional.empty(); // C14: sem substância → sem Decisão
        }
        DecisionTipo tipo = DecisionLog.tipoFor(passage.area());
        Decision decisao = new Decision(UUID.randomUUID(), passage.companyId(), passage.area(),
                tipo, passage.origem(), passage.oQueMudou(), passage.racional(),
                passage.racionalTipo(), passage.racionalArrancado())
                .comGrau(passage.grau());
        if (passage.supersedesId() != null) {
            decisao.superseding(passage.supersedesId());
        }
        switch (passage.anchor()) {
            case Anchor.NaApproval aprovacao -> decisao.anchoredToApproval(aprovacao.approvalId());
            case Anchor.NaReview review -> decisao.anchoredToReview(review.reviewId(), review.issueId());
        }
        if (passage.racionalTipo() == RacionalTipo.INTUICAO) {
            decisao.withEvidencia(racionalPolicy.maieuticProbe()); // D10: acompanha, nunca bloqueia
        }
        decisionLog.record(decisao);
        if (tipo == DecisionTipo.DIRECAO) {
            consolidator.refresh(passage.companyId()); // a carta é projeção — mesma tx do gate
        }
        return Optional.of(decisao);
    }

    /**
     * O seam da passagem (C15): tudo que uma porta sabe sobre o commit do dono. {@code area} é a
     * do ARTEFATO decidido (C11 — na review, a área da Issue; nunca default fixo). {@code grau}
     * vem da PORTA (C3); {@code supersedesId} é a linhagem D11 já resolvida de {@code #N} pra id.
     */
    public record GatePassage(UUID companyId, String area, DecisionOrigem origem, String oQueMudou,
                              String racional, RacionalTipo racionalTipo, boolean racionalArrancado,
                              UUID supersedesId, DecisionGrau grau, Anchor anchor) {
    }

    /** A proveniência da Decisão no gate que a fabricou (D5): qual porta, apontando o quê. */
    public sealed interface Anchor {
        record NaApproval(UUID approvalId) implements Anchor {
        }

        record NaReview(UUID reviewId, UUID issueId) implements Anchor {
        }
    }
}
