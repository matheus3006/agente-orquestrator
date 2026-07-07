package com.orquestrador.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A DECISÃO é a unidade atômica endereçável da memória (ADR-0019/D4) — a Issue é contêiner de
 * execução, a Decisão é unidade de memória. Nasce datada (achável por data/área/tipo — o Log de
 * Decisões filtrável, D5) e IMUTÁVEL por linhagem: revisar = nova Decisão com {@code supersedes}
 * apontando a velha (D11), nunca UPDATE. O racional é OBRIGATÓRIO (D6, regime de presença;
 * "porque quis" já foi rejeitado no gate antes de chegar aqui — ADR-0020). Os ponteiros
 * ({@code approval_id}/{@code review_id}/{@code issue_id}) ancoram a proveniência no gate que a
 * fabricou (D5); {@code o_que_mudou} é o ponteiro legível pro elemento decidido.
 *
 * <p>Residual 3 do ADR-0019 FECHADO aqui: o Decision-log mora em tabela própria {@code decision}
 * no Postgres (não em company_memory — a carta é PROJEÇÃO sobre estas linhas, tier acima).
 */
@Entity
@Table(name = "decision")
public class Decision {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();

    @Column(nullable = false, columnDefinition = "text")
    private String area;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionTipo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionOrigem origem;

    @Column(name = "o_que_mudou", nullable = false, columnDefinition = "text")
    private String oQueMudou;

    @Column(nullable = false, columnDefinition = "text")
    private String racional;

    @Enumerated(EnumType.STRING)
    @Column(name = "racional_tipo", nullable = false)
    private RacionalTipo racionalTipo;

    @Column(name = "racional_arrancado", nullable = false)
    private boolean racionalArrancado;

    @Column(name = "supersedes_id")
    private UUID supersedesId;

    @Column(columnDefinition = "text")
    private String evidencia;

    @Column(name = "issue_id")
    private UUID issueId;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "review_id")
    private UUID reviewId;

    protected Decision() {
    }

    public Decision(UUID id, UUID companyId, String area, DecisionTipo tipo, DecisionOrigem origem,
                    String oQueMudou, String racional, RacionalTipo racionalTipo, boolean racionalArrancado) {
        this.id = id;
        this.companyId = companyId;
        this.area = area;
        this.tipo = tipo;
        this.origem = origem;
        this.oQueMudou = oQueMudou;
        this.racional = racional;
        this.racionalTipo = racionalTipo;
        this.racionalArrancado = racionalArrancado;
        this.decidedAt = Instant.now();
    }

    public Decision anchoredToApproval(UUID approvalId) {
        this.approvalId = approvalId;
        return this;
    }

    public Decision anchoredToReview(UUID reviewId, UUID issueId) {
        this.reviewId = reviewId;
        this.issueId = issueId;
        return this;
    }

    public Decision withEvidencia(String evidencia) {
        this.evidencia = evidencia;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getArea() {
        return area;
    }

    public DecisionTipo getTipo() {
        return tipo;
    }

    public DecisionOrigem getOrigem() {
        return origem;
    }

    public String getOQueMudou() {
        return oQueMudou;
    }

    public String getRacional() {
        return racional;
    }

    public RacionalTipo getRacionalTipo() {
        return racionalTipo;
    }

    public boolean isRacionalArrancado() {
        return racionalArrancado;
    }

    public UUID getSupersedesId() {
        return supersedesId;
    }

    public String getEvidencia() {
        return evidencia;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public UUID getReviewId() {
        return reviewId;
    }
}
