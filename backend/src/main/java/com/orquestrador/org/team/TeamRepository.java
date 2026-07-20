package com.orquestrador.org.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByCompanyIdOrderByDominioAscFocoAsc(UUID companyId);

    Optional<Team> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndDominioAndFoco(UUID companyId, String dominio, String foco);

    /**
     * Resolve o time pela identidade (company, dominio, foco) — o lookup da absorção (ADR-0037,
     * M5): o bloco {@code ```absorcao```} aponta o TIME por dominio/foco e o sistema resolve
     * time→arquiteto. O unique {@code uq_team_company_dominio_foco} garante no máximo um.
     */
    Optional<Team> findByCompanyIdAndDominioAndFoco(UUID companyId, String dominio, String foco);

    /** Os filhos diretos na árvore-organograma (ADR-0045): o passo do walk que DESCE a subárvore. */
    List<Team> findByParentTeamId(UUID parentTeamId);

    /**
     * Os times que um agente GERE (titular do posto de gestor) — a 1ª condição do guard do
     * dirigir-sem-gate (ADR-0047, D-DSG-2): quem não é gestor de time nenhum não dirige nada.
     *
     * <p>{@code OrderById} não é enfeite (hardening do PR #50): sem ele a lista saía na ordem
     * arbitrária das linhas, e os dois consumidores escolhem o braço com {@code .findFirst()} /
     * seleção — mesma entrada podia dar braço diferente entre execuções. O {@link
     * com.orquestrador.governance.parecer.ParecerGate} ainda ordena por PRIVILÉGIO por cima disto
     * (menos privilegiado vence); aqui a garantia é só de estabilidade, e ela também alcança o
     * {@code DirigirGate}, que segue com {@code findFirst}.
     */
    List<Team> findByCompanyIdAndGestorAgentIdOrderById(UUID companyId, UUID gestorAgentId);
}
