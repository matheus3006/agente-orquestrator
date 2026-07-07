package com.orquestrador.identity.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, UUID> {

    boolean existsByCompanyIdAndUserId(UUID companyId, UUID userId);

    List<CompanyMembership> findByUserId(UUID userId);
}
