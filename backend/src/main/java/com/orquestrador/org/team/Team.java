package com.orquestrador.org.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * TIME = entidade real (ADR-0017, T.2): domínio (macro: Dev/Marketing) + foco (livre) identificam o
 * time na company (unique declarado — H2 força). O arquiteto é DESIGNADO e pode ser nulo: a trava
 * "time sem arquiteto não dispara trabalho" vive no DISPATCH ({@link TeamService#assertDispatchAllowed}),
 * não na criação — o time monta aos poucos.
 */
@Entity
@Table(name = "team", uniqueConstraints = {
        @UniqueConstraint(name = "uq_team_company_dominio_foco",
                columnNames = {"company_id", "dominio", "foco"})
})
public class Team {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String dominio;

    @Column(nullable = false)
    private String foco;

    // O agente designado arquiteto — SEMPRE membro do time (invariante mantido por construção no
    // TeamService: sair do time limpa o posto). Nulo = time ainda sem arquiteto (dispatch travado).
    @Column(name = "arquiteto_agent_id")
    private UUID arquitetoAgentId;

    // T.4: quanto trabalho CONCORRENTE o time do arquiteto pode espalhar (runs ativos dos membros).
    @Column(name = "fan_out_cap", nullable = false)
    private int fanOutCap = 5;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Team() {
    }

    public Team(UUID id, UUID companyId, String dominio, String foco) {
        this.id = id;
        this.companyId = companyId;
        this.dominio = dominio;
        this.foco = foco;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getDominio() {
        return dominio;
    }

    public String getFoco() {
        return foco;
    }

    public UUID getArquitetoAgentId() {
        return arquitetoAgentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Designa (ou limpa, com null) o arquiteto. A validação de membership é do TeamService. */
    public void designarArquiteto(UUID arquitetoAgentId) {
        this.arquitetoAgentId = arquitetoAgentId;
    }

    /** O time tem arquiteto designado? (a condição da trava do dispatch) */
    public boolean temArquiteto() {
        return arquitetoAgentId != null;
    }

    public int getFanOutCap() {
        return fanOutCap;
    }

    /** Redefine o cap de fan-out do time (T.4). Não-positivo é recusado: cap zero = time morto. */
    public void definirFanOutCap(int fanOutCap) {
        if (fanOutCap <= 0) {
            throw new IllegalArgumentException("fan_out_cap tem que ser positivo");
        }
        this.fanOutCap = fanOutCap;
    }
}
