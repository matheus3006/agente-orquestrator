package com.orquestrador.org.agent;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.org.roletemplate.RoleTemplateVersion;
import com.orquestrador.org.roletemplate.RoleTemplateVersionRepository;
import com.orquestrador.org.team.TeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/companies/{companyId}/agents")
public class AgentController {

    private final AgentRepository agents;
    private final AgentHireService hireService;
    private final ChainOfCommandService chain;
    private final TeamService teamService;
    private final RoleTemplateVersionRepository templateVersions;
    private final CompanyAccessGuard guard;

    public AgentController(AgentRepository agents, AgentHireService hireService,
                           ChainOfCommandService chain, TeamService teamService,
                           RoleTemplateVersionRepository templateVersions, CompanyAccessGuard guard) {
        this.agents = agents;
        this.hireService = hireService;
        this.chain = chain;
        this.teamService = teamService;
        this.templateVersions = templateVersions;
        this.guard = guard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentResponse create(@PathVariable UUID companyId, @RequestBody @Valid CreateAgentRequest request) {
        // Contratar é do operador: o hire-com-role dispara install de skill (board-only no resto
        // do sistema); spawn de agente por agente é T.4, outra porta.
        guard.assertBoardMember(companyId);
        var actor = guard.currentActor();
        // Hire-com-role (ADR-0017): com roleTemplateVersionId, o serviço copia o snapshot do preset
        // (motor + skillset + pin); sem ele, é o hire avulso de sempre. O motor/skillset ficam no serviço.
        Agent agent = hireService.hire(companyId, new AgentHireService.HireCommand(
                request.name(), request.role(), request.reportsToId(), request.adapterType(),
                request.runtimeCommand(), request.executionTarget(), request.model(), request.effort(),
                request.roleTemplateVersionId(), null), actor.type(), actor.id());
        return toResponse(agent);
    }

    // Promover o motor (ADR-0014/Q6): o dropdown do detail manda o trio adapterType+model+effort.
    @PatchMapping("/{agentId}")
    @Transactional
    public AgentResponse updateEngine(@PathVariable UUID companyId, @PathVariable UUID agentId,
                                      @RequestBody @Valid UpdateEngineRequest request) {
        guard.assertMember(companyId);
        Agent agent = agents.findById(agentId)
                .filter(candidate -> candidate.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Agente nao encontrado: " + agentId));
        agent.configureEngine(request.adapterType(), request.model(), request.effort());
        return toResponse(agents.save(agent));
    }

    // Cadeia de comando (T.2): reparent é ESTRUTURA organizacional → board-only (como o hire
    // pós-R1). Sub-recurso próprio porque null é VALOR legal (vira raiz) — num PATCH genérico não
    // dá pra distinguir "ausente" de "null". Anti-ciclo no ChainOfCommandService (o canvas espelha).
    @PatchMapping("/{agentId}/reports-to")
    public AgentResponse reparent(@PathVariable UUID companyId, @PathVariable UUID agentId,
                                  @RequestBody ReparentRequest request) {
        guard.assertBoardMember(companyId);
        return toResponse(chain.reparent(companyId, agentId, request.reportsToId()));
    }

    // Pertencimento a time (T.2): idem — board-only, null = sai do time. O TeamService mantém o
    // invariante arquiteto-é-membro (sair do time limpa o posto do time antigo).
    @PatchMapping("/{agentId}/team")
    public AgentResponse assignTeam(@PathVariable UUID companyId, @PathVariable UUID agentId,
                                    @RequestBody AssignTeamRequest request) {
        guard.assertBoardMember(companyId);
        return toResponse(teamService.assignAgent(companyId, agentId, request.teamId()));
    }

    // Re-pin COMPLETO (R10-A/D5-D6): re-aplica o preset da versão no agente (motor + skillset) e move o
    // pin — SUBSTITUI (a versão manda; ajustes manuais descartados). Mão soberana do operador: ação
    // LOCAL (um agente, uma company) → board-only-DIRETO, sem gate (o gate é só pro molde global, G4).
    @PostMapping("/{agentId}/repin")
    public AgentResponse repin(@PathVariable UUID companyId, @PathVariable UUID agentId,
                               @RequestBody @Valid RepinRequest request) {
        guard.assertBoardMember(companyId);
        var actor = guard.currentActor();
        Agent agent = hireService.repin(companyId, agentId, request.roleTemplateVersionId(),
                actor.type(), actor.id());
        return toResponse(agent);
    }

    @GetMapping
    public List<AgentResponse> list(@PathVariable UUID companyId) {
        guard.assertMember(companyId);
        List<Agent> roster = agents.findByCompanyId(companyId);
        // Batch do read-join do drift-badge (D4): resolve todos os pinnedTemplateId numa query só
        // (sem N+1), depois mapeia. O drift em si é derivado NA TELA (pinada ≠ corrente do molde).
        Set<UUID> pinnedVersionIds = roster.stream().map(Agent::getPinnedTemplateVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UUID> templateIdByVersion = templateVersions.findAllById(pinnedVersionIds).stream()
                .collect(Collectors.toMap(RoleTemplateVersion::getId, RoleTemplateVersion::getRoleTemplateId));
        return roster.stream()
                .map(agent -> AgentResponse.from(agent,
                        templateIdByVersion.get(agent.getPinnedTemplateVersionId())))
                .toList();
    }

    /** O molde (roleTemplateId) da versão pinada — read-join pro drift-badge; nulo se sem pin/versão sumida. */
    private AgentResponse toResponse(Agent agent) {
        UUID pinnedTemplateId = agent.getPinnedTemplateVersionId() == null ? null
                : templateVersions.findById(agent.getPinnedTemplateVersionId())
                        .map(RoleTemplateVersion::getRoleTemplateId).orElse(null);
        return AgentResponse.from(agent, pinnedTemplateId);
    }

    public record CreateAgentRequest(@NotBlank String name, @NotBlank String role, UUID reportsToId,
                                     String adapterType, String runtimeCommand, String executionTarget,
                                     String model, String effort, UUID roleTemplateVersionId) {
    }

    public record UpdateEngineRequest(String adapterType, String model, String effort) {
    }

    /** {@code reportsToId} nulo = vira raiz da cadeia. */
    public record ReparentRequest(UUID reportsToId) {
    }

    /** {@code teamId} nulo = sai do time. */
    public record AssignTeamRequest(UUID teamId) {
    }

    /** O alvo do re-pin: a versão do molde a re-aplicar (R10-A/D5). */
    public record RepinRequest(@NotNull UUID roleTemplateVersionId) {
    }

    // pinnedTemplateId = o molde (roleTemplateId) da versão pinada — read-join pro drift-badge: com ele
    // + o currentVersionId do molde (já exposto), a tela deriva drift (pinada ≠ corrente) sem estado novo.
    public record AgentResponse(UUID id, UUID companyId, String name, String role, String persona,
                                UUID reportsToId, String adapterType, String executionTarget, String model,
                                String effort, UUID pinnedTemplateVersionId, UUID pinnedTemplateId,
                                UUID teamId, Instant createdAt) {
        static AgentResponse from(Agent agent, UUID pinnedTemplateId) {
            return new AgentResponse(agent.getId(), agent.getCompanyId(), agent.getName(),
                    agent.getRole(), agent.getPersona(), agent.getReportsToId(), agent.getAdapterType(),
                    agent.getExecutionTarget(), agent.getModel(), agent.getEffort(),
                    agent.getPinnedTemplateVersionId(), pinnedTemplateId, agent.getTeamId(),
                    agent.getCreatedAt());
        }
    }
}
