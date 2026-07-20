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
     * arbitrária das linhas, e mesma entrada podia dar braço diferente entre execuções. Mas
     * estabilidade NÃO é fail-safe — ordenar por UUID aleatório é repetível e continua sorteando.
     * Por isso os <b>dois</b> consumidores ordenam por PRIVILÉGIO por cima disto (menos
     * privilegiado vence, {@code id} só como desempate): o {@link
     * com.orquestrador.governance.parecer.ParecerGate} desde o hardening do PR #50, e o {@code
     * DirigirGate} desde o fecho do parecer (conferência dos PRs #51/#52, 🟠 2).
     *
     * <p>A forma anterior desta nota dizia que no {@code DirigirGate} "a garantia é só de
     * estabilidade" porque lá "a consequência é sob qual braço a issue nasce, não se um humano
     * está no laço". <b>Era falso</b>, e a mesma frase tinha sido transcrita para a ADR-0048
     * §Hardening item 5: se vence o braço PROMOVIDO a issue nasce born-APPROVED e o dono nunca a
     * vê; se vence o ESTÁGIO o cartão para PENDING no gate. A consequência é precisamente "se um
     * humano está no laço".
     */
    List<Team> findByCompanyIdAndGestorAgentIdOrderById(UUID companyId, UUID gestorAgentId);
}
