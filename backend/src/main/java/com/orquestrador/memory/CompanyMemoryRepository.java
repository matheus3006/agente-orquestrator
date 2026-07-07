package com.orquestrador.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompanyMemoryRepository extends JpaRepository<CompanyMemory, UUID> {

    /** A carta corrente = a versão mais alta do kind (append-only; nunca UPDATE de body). */
    Optional<CompanyMemory> findFirstByCompanyIdAndKindOrderByVersionDesc(UUID companyId, MemoryKind kind);
}
