package com.orquestrador.org.roletemplate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catálogo de moldes. No v1 os moldes são todos GLOBAIS ({@code company_id IS NULL}); a coluna
 * {@code company_id} fica para o override por-company de T.2. O ramo {@code CompanyIdIsNull} nos
 * derived queries é o que lidera a leitura pelo escopo — cuidado deliberado com o {@code IS NULL}
 * (não colar {@code = null} num JPQL).
 */
public interface RoleTemplateRepository extends JpaRepository<RoleTemplate, UUID> {

    /** O catálogo GLOBAL servido à UI (board-only), em ordem determinística pela role. */
    List<RoleTemplate> findByCompanyIdIsNullOrderByRoleEspecificaAsc();

    /** Pré-check da unicidade da role GLOBAL (o índice parcial where company_id is null é Postgres-only). */
    Optional<RoleTemplate> findByCompanyIdIsNullAndRoleEspecifica(String roleEspecifica);
}
