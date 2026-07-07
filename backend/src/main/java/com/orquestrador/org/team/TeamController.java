package com.orquestrador.org.team;

import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Superfície de TIMES (T.2, ADR-0017). Estrutura organizacional é GOVERNANÇA: mutações são
 * board-only (coerente com o hire pós-R1); a leitura é member — o organograma é dado da company.
 * O arquiteto tem sub-recurso próprio ({@code PATCH .../arquiteto}) porque null é VALOR legal
 * (limpar o posto) — não dá pra distinguir ausente-de-null num PATCH genérico.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/teams")
public class TeamController {

    private final TeamService service;
    private final CompanyAccessGuard guard;

    public TeamController(TeamService service, CompanyAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping
    public List<TeamResponse> list(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        return service.list(companyId).stream().map(TeamResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@PathVariable UUID companyId, @RequestBody @Valid CreateTeamRequest request) {
        guard.assertBoardMember(companyId);
        return TeamResponse.from(service.create(companyId, request.dominio(), request.foco()));
    }

    @PatchMapping("/{teamId}/arquiteto")
    public TeamResponse setArquiteto(@PathVariable UUID companyId, @PathVariable UUID teamId,
                                     @RequestBody SetArquitetoRequest request) {
        guard.assertBoardMember(companyId);
        return TeamResponse.from(service.setArquiteto(companyId, teamId, request.agentId()));
    }

    public record CreateTeamRequest(@NotBlank String dominio, @NotBlank String foco) {
    }

    /** {@code agentId} nulo LIMPA o posto (o dispatch do time volta a travar). */
    public record SetArquitetoRequest(UUID agentId) {
    }

    public record TeamResponse(UUID id, UUID companyId, String dominio, String foco,
                               UUID arquitetoAgentId, Instant createdAt) {
        static TeamResponse from(Team team) {
            return new TeamResponse(team.getId(), team.getCompanyId(), team.getDominio(),
                    team.getFoco(), team.getArquitetoAgentId(), team.getCreatedAt());
        }
    }
}
