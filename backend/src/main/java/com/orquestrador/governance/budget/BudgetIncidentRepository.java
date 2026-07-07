package com.orquestrador.governance.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BudgetIncidentRepository extends JpaRepository<BudgetIncident, UUID> {

    boolean existsByScopeTypeAndScopeIdAndStatus(ScopeType scopeType, UUID scopeId, IncidentStatus status);

    List<BudgetIncident> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
}
