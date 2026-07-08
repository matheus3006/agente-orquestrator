package com.orquestrador.org.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineCatalogRepository extends JpaRepository<EngineCatalog, UUID> {

    /** As combinações servidas à UI: só as habilitadas, em ordem determinística pelo label legível. */
    List<EngineCatalog> findByEnabledTrueOrderByLabelAsc();

    /**
     * Resolve o engine de um agente (adapter+model+effort) para sua linha do catálogo — o denominador
     * do gatilho de handoff (ADR-0030/M1). Combinação não catalogada (ou o {@code process}, sem
     * model/effort) ⟹ vazio ⟹ fail-open (D9): sem janela, sem handoff.
     */
    Optional<EngineCatalog> findFirstByAdapterTypeAndModelAndEffort(String adapterType, String model, String effort);
}
