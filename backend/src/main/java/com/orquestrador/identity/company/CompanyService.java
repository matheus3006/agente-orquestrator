package com.orquestrador.identity.company;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.user.AppUser;
import com.orquestrador.identity.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;

    public CompanyService(CompanyRepository companies, CompanyMembershipRepository memberships, AppUserRepository users) {
        this.companies = companies;
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional
    public Company create(String name, String slug, UUID ownerUserId) {
        if (companies.existsBySlug(slug)) {
            throw new ConflictException("Slug ja em uso: " + slug);
        }
        users.findById(ownerUserId).orElseGet(() ->
                users.save(new AppUser(ownerUserId, ownerUserId + "@local", "Board User")));
        Company company = companies.save(new Company(UUID.randomUUID(), name, slug));
        memberships.save(new CompanyMembership(UUID.randomUUID(), company.getId(), ownerUserId, "OWNER"));
        return company;
    }

    @Transactional(readOnly = true)
    public List<Company> listForActor(Actor actor) {
        if (actor.type() == ActorType.AGENT) {
            return companies.findById(actor.companyId()).map(List::of).orElseGet(List::of);
        }
        List<UUID> ids = memberships.findByUserId(actor.id()).stream()
                .map(CompanyMembership::getCompanyId)
                .toList();
        return ids.isEmpty() ? List.of() : companies.findByIdIn(ids);
    }

    @Transactional(readOnly = true)
    public Company get(UUID companyId) {
        return companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company nao encontrada: " + companyId));
    }
}
