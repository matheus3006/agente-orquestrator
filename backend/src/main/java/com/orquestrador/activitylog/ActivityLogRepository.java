package com.orquestrador.activitylog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    List<ActivityLog> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    /** Feed do board (F.0): os N mais recentes — o limit chega clampado do controller. */
    List<ActivityLog> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /**
     * F.12 (ADR-0026, B4): o evento durável mais recente de uma ação sobre uma entidade — o
     * preflight do start usa o {@code WORKSPACE_COLLECTED} de uma Issue (com a data da coleta)
     * para responder o aviso legível em vez de subir sobre um volume vazio.
     */
    Optional<ActivityLog> findFirstByCompanyIdAndEntityIdAndActionOrderByCreatedAtDesc(
            UUID companyId, UUID entityId, String action);
}
