package com.orquestrador.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    List<Decision> findByCompanyIdOrderByDecidedAtDesc(UUID companyId);

    /** A projeção da carta lê SÓ direção, em ordem cronológica (determinística). */
    List<Decision> findByCompanyIdAndTipoOrderByDecidedAtAscIdAsc(UUID companyId, DecisionTipo tipo);
}
