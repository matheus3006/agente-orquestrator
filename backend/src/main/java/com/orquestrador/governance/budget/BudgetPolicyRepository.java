package com.orquestrador.governance.budget;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BudgetPolicyRepository extends JpaRepository<BudgetPolicy, UUID> {

    List<BudgetPolicy> findByScopeTypeAndScopeId(ScopeType scopeType, UUID scopeId);

    List<BudgetPolicy> findByCompanyId(UUID companyId);
}
