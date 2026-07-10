package com.orquestrador.identity.company;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    boolean existsBySlug(String slug);

    List<Company> findByIdIn(Collection<UUID> ids);

    /**
     * Lock pessimista na linha da Company (SELECT ... FOR UPDATE). Serializa as criacoes
     * concorrentes de Issue da mesma company (ADR-0033: dono na UI + approve do CEO correm
     * em threads Tomcat) — garante o MAX+1 do seq denso e sem duplicata de forma
     * multi-no-safe e testavel no H2 (independe de indice parcial Postgres).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") UUID id);
}
