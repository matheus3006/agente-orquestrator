package com.orquestrador.identity.company;

import com.orquestrador.common.error.ForbiddenException;
import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyAccessGuard guard;

    public CompanyController(CompanyService companyService, CompanyAccessGuard guard) {
        this.companyService = companyService;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@RequestBody @Valid CreateCompanyRequest request) {
        Actor actor = guard.currentActor();
        if (actor == null) {
            throw new ForbiddenException("Apenas um ator autenticado pode criar companies");
        }
        return CompanyResponse.from(companyService.create(request.name(), request.slug(), actor.id()));
    }

    @GetMapping
    public List<CompanyResponse> list() {
        Actor actor = guard.currentActor();
        if (actor == null) {
            throw new ForbiddenException("Requisicao sem ator");
        }
        return companyService.listForActor(actor).stream().map(CompanyResponse::from).toList();
    }

    @GetMapping("/{companyId}")
    public CompanyResponse get(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        return CompanyResponse.from(companyService.get(companyId));
    }

    public record CreateCompanyRequest(@NotBlank String name, @NotBlank String slug) {
    }

    public record CompanyResponse(UUID id, String name, String slug, Instant createdAt) {
        static CompanyResponse from(Company company) {
            return new CompanyResponse(company.getId(), company.getName(), company.getSlug(), company.getCreatedAt());
        }
    }
}
