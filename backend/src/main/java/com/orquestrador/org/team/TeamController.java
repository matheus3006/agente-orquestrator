package com.orquestrador.org.team;

import com.orquestrador.common.security.Actor;
import com.orquestrador.common.security.ActorType;
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
        return TeamResponse.from(service.create(companyId, request.dominio(), request.foco(),
                request.parentTeamId()));
    }

    @PatchMapping("/{teamId}/arquiteto")
    public TeamResponse setArquiteto(@PathVariable UUID companyId, @PathVariable UUID teamId,
                                     @RequestBody SetArquitetoRequest request) {
        guard.assertBoardMember(companyId);
        return TeamResponse.from(service.setArquiteto(companyId, teamId, request.agentId()));
    }

    // O posto de GESTOR (ADR-0045): sub-recurso próprio pela mesma razão do arquiteto — null é
    // VALOR legal (limpar o posto). Trocar o gestor RE-DERIVA a chefia da subárvore no mesmo ato E
    // reseta o marcador de autoridade delegada pra ESTAGIO (ADR-0046 — ver TeamService.setGestor).
    @PatchMapping("/{teamId}/gestor")
    public TeamResponse setGestor(@PathVariable UUID companyId, @PathVariable UUID teamId,
                                  @RequestBody SetGestorRequest request) {
        guard.assertBoardMember(companyId);
        return TeamResponse.from(service.setGestor(companyId, teamId, request.agentId()));
    }

    // A promoção do gestor titular (ADR-0046, D-ORG-2): endpoint direto board-only, mesmo molde do
    // `/gestor` — ato soberano do dono, sem proposta de agente, então não passa pelo gate de
    // aprovação (ApprovalActionType é reservado pra "agente propõe, board decide"). Transição só
    // ESTAGIO→PROMOVIDO; sem gestor designado ou já promovido é 409 acionável.
    @PatchMapping("/{teamId}/gestor/promover")
    public TeamResponse promoverGestor(@PathVariable UUID companyId, @PathVariable UUID teamId) {
        guard.assertBoardMember(companyId);
        Actor actor = guard.currentActor();
        return TeamResponse.from(service.promoverGestor(companyId, teamId, actor.type(), actor.id()));
    }

    // Mover o time na árvore-organograma (ADR-0045): null = sobe pra RAIZ (vira departamento).
    // Anti-ciclo no TeamService; a subárvore inteira re-deriva a chefia.
    @PatchMapping("/{teamId}/parent")
    public TeamResponse reparent(@PathVariable UUID companyId, @PathVariable UUID teamId,
                                 @RequestBody ReparentTeamRequest request) {
        guard.assertBoardMember(companyId);
        return TeamResponse.from(service.reparentTeam(companyId, teamId, request.parentTeamId()));
    }

    /** {@code parentTeamId} nulo = time pendurado direto na EMPRESA (raiz) — um "departamento". */
    public record CreateTeamRequest(@NotBlank String dominio, @NotBlank String foco,
                                    UUID parentTeamId) {
    }

    /** {@code agentId} nulo LIMPA o posto (o dispatch do time volta a travar). */
    public record SetArquitetoRequest(UUID agentId) {
    }

    /** {@code agentId} nulo LIMPA o posto (a chefia dos membros volta a subir pro time-pai). */
    public record SetGestorRequest(UUID agentId) {
    }

    /** {@code parentTeamId} nulo = sobe pra raiz. */
    public record ReparentTeamRequest(UUID parentTeamId) {
    }

    /**
     * {@code gestorStatus}/{@code gestorPromovidoEm}/{@code gestorPromovidoPorActor*} (ADR-0046):
     * o marcador de autoridade delegada do gestor titular — quem/quando promoveu, ou tudo null
     * quando o time nunca teve gestor promovido (ou está em ESTAGIO após reset por troca).
     */
    public record TeamResponse(UUID id, UUID companyId, String dominio, String foco,
                               UUID arquitetoAgentId, UUID gestorAgentId, UUID parentTeamId,
                               GestorStatus gestorStatus, Instant gestorPromovidoEm,
                               ActorType gestorPromovidoPorActorType, UUID gestorPromovidoPorActorId,
                               Instant createdAt) {
        static TeamResponse from(Team team) {
            return new TeamResponse(team.getId(), team.getCompanyId(), team.getDominio(),
                    team.getFoco(), team.getArquitetoAgentId(), team.getGestorAgentId(),
                    team.getParentTeamId(), team.getGestorStatus(), team.getGestorPromovidoEm(),
                    team.getGestorPromovidoPorActorType(), team.getGestorPromovidoPorActorId(),
                    team.getCreatedAt());
        }
    }
}
