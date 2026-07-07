package com.orquestrador.identity.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company_membership")
public class CompanyMembership {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CompanyMembership() {
    }

    public CompanyMembership(UUID id, UUID companyId, UUID userId, String role) {
        this.id = id;
        this.companyId = companyId;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}
