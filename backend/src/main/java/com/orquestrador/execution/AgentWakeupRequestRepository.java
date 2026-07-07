package com.orquestrador.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentWakeupRequestRepository extends JpaRepository<AgentWakeupRequest, UUID> {

    List<AgentWakeupRequest> findByRunId(UUID runId);

    List<AgentWakeupRequest> findByRunIdAndStatus(UUID runId, WakeupStatus status);

    /** Hard-stop reativo: cancela wakeups pendentes do escopo. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentWakeupRequest w set w.status = :to where w.companyId = :companyId and w.status in :from")
    int cancelByCompany(@Param("companyId") UUID companyId,
                        @Param("from") Collection<WakeupStatus> from, @Param("to") WakeupStatus to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentWakeupRequest w set w.status = :to where w.agentId = :agentId and w.status in :from")
    int cancelByAgent(@Param("agentId") UUID agentId,
                      @Param("from") Collection<WakeupStatus> from, @Param("to") WakeupStatus to);
}
