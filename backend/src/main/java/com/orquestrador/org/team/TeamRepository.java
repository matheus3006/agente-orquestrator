package com.orquestrador.org.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByCompanyIdOrderByDominioAscFocoAsc(UUID companyId);

    Optional<Team> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndDominioAndFoco(UUID companyId, String dominio, String foco);
}
