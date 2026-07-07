package com.orquestrador.org.roletemplate;

import com.orquestrador.common.security.CompanyAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Superfície do catálogo GLOBAL de MOLDES (ADR-0017/0018): board-only, company-scoped só para gatear
 * acesso (clona {@link com.orquestrador.org.engine.EngineCatalogController}). O gate é por ALCANCE —
 * criar/versionar molde mexe no catálogo global (todas as empresas do dono) → {@code assertBoardMember}.
 * A biblioteca master-detail do canvas (T.1-FE) consome estes endpoints.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/role-templates")
public class RoleTemplateController {

    private final RoleTemplateService service;
    private final RoleTemplateRepository templates;
    private final CompanyAccessGuard guard;

    public RoleTemplateController(RoleTemplateService service, RoleTemplateRepository templates,
                                  CompanyAccessGuard guard) {
        this.service = service;
        this.templates = templates;
        this.guard = guard;
    }

    @GetMapping
    public List<RoleTemplateResponse> list(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        return templates.findByCompanyIdIsNullOrderByRoleEspecificaAsc().stream()
                .map(RoleTemplateResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleTemplateResponse create(@PathVariable UUID companyId,
                                       @RequestBody @Valid CreateTemplateRequest request) {
        guard.assertBoardMember(companyId);
        UUID actorId = guard.currentActor().id();
        RoleTemplate template = service.createTemplate(request.tipoFuncao(), request.roleEspecifica(),
                new RoleTemplateService.Preset(request.adapterType(), request.model(), request.effort(),
                        request.skillKeys(), request.reportsToDefault()),
                request.changeDescription(), actorId);
        return RoleTemplateResponse.from(template);
    }

    @PostMapping("/{templateId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleTemplateVersionResponse addVersion(@PathVariable UUID companyId, @PathVariable UUID templateId,
                                                  @RequestBody @Valid CreateVersionRequest request) {
        guard.assertBoardMember(companyId);
        UUID actorId = guard.currentActor().id();
        RoleTemplateVersion version = service.newVersion(templateId,
                new RoleTemplateService.Preset(request.adapterType(), request.model(), request.effort(),
                        request.skillKeys(), request.reportsToDefault()),
                request.changeType(), request.changeDescription(), actorId);
        return RoleTemplateVersionResponse.from(version, service.skillKeys(version));
    }

    @GetMapping("/{templateId}/versions")
    public List<RoleTemplateVersionResponse> savelog(@PathVariable UUID companyId, @PathVariable UUID templateId) {
        guard.assertBoardMember(companyId);
        return service.savelog(templateId).stream()
                .map(version -> RoleTemplateVersionResponse.from(version, service.skillKeys(version)))
                .toList();
    }

    public record CreateTemplateRequest(@NotNull TipoFuncao tipoFuncao, @NotBlank String roleEspecifica,
                                        String adapterType, String model, String effort,
                                        List<String> skillKeys, String reportsToDefault,
                                        String changeDescription) {
    }

    public record CreateVersionRequest(String adapterType, String model, String effort, List<String> skillKeys,
                                       String reportsToDefault, String changeType, String changeDescription) {
    }

    public record RoleTemplateResponse(UUID id, TipoFuncao tipoFuncao, String roleEspecifica,
                                       UUID currentVersionId, Instant createdAt) {
        static RoleTemplateResponse from(RoleTemplate template) {
            return new RoleTemplateResponse(template.getId(), template.getTipoFuncao(),
                    template.getRoleEspecifica(), template.getCurrentVersionId(), template.getCreatedAt());
        }
    }

    // O preset por inteiro (trio do motor + skillKeys + reports_to sugerido): o savelog da biblioteca
    // lê daqui — os skillKeys chegam já desserializados do jsonb pelo service.
    public record RoleTemplateVersionResponse(UUID id, UUID roleTemplateId, int version, String adapterType,
                                              String model, String effort, List<String> skillKeys,
                                              String reportsToDefault, String status, Instant createdAt) {
        static RoleTemplateVersionResponse from(RoleTemplateVersion version, List<String> skillKeys) {
            return new RoleTemplateVersionResponse(version.getId(), version.getRoleTemplateId(),
                    version.getVersion(), version.getAdapterType(), version.getModel(), version.getEffort(),
                    skillKeys, version.getReportsToDefault(), version.getStatus(), version.getCreatedAt());
        }
    }
}
