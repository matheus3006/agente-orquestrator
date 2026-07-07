package com.orquestrador.identity.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CompanyRepository extends JpaRepository<Company, UUID> {

    boolean existsBySlug(String slug);

    List<Company> findByIdIn(Collection<UUID> ids);
}
