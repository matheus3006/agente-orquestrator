package com.orquestrador.org.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByCompanyId(UUID companyId);

    /**
     * Lock pessimista na linha do Agent (SELECT ... FOR UPDATE). Serializa os wakeups
     * concorrentes do mesmo Agent — garante 1 Run ativo por Agent de forma multi-no-safe
     * e testavel no H2 (independe de indice parcial Postgres).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.id = :id")
    Optional<Agent> findByIdForUpdate(@Param("id") UUID id);

    /** O CEO da company é RESOLVIDO por role (ADR-0018); o mais antigo é o canônico se houver mais de um. */
    Optional<Agent> findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(UUID companyId, String role);

    /** A persona de CEO da company (ADR-0031, D2): o balcão único resolve por (role, persona). */
    Optional<Agent> findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(UUID companyId, String role,
                                                                             String persona);
}
