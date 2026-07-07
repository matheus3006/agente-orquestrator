package com.orquestrador.org.engine;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EngineCatalogRepository extends JpaRepository<EngineCatalog, UUID> {

    /** As combinações servidas à UI: só as habilitadas, em ordem determinística pelo label legível. */
    List<EngineCatalog> findByEnabledTrueOrderByLabelAsc();
}
