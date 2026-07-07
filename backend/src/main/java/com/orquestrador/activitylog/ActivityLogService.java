package com.orquestrador.activitylog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.common.security.ActorType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writer explícito do activity log (ADR-0009) — não Hibernate Envers. Toda ação mutante governada
 * chama {@link #record}. O {@code detail} é serializado como JSON e DEVE conter só IDs/enums/números
 * (responsabilidade do chamador); nunca body/segredo/PII.
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository repository;
    private final ObjectMapper objectMapper;

    public ActivityLogService(ActivityLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ActivityLog record(UUID companyId, ActorType actorType, UUID actorId, ActivityAction action,
                              String entityType, UUID entityId, Map<String, Object> detail) {
        String detailJson = (detail == null || detail.isEmpty()) ? null : toJson(detail);
        return repository.save(new ActivityLog(UUID.randomUUID(), companyId,
                actorType != null ? actorType.name() : null, actorId, action.name(),
                entityType, entityId, detailJson));
    }

    // Degrada em vez de lançar: um detail problemático nunca deve impedir a gravação do evento de
    // auditoria (ADR-0009). O contrato (só IDs/enums/números) é responsabilidade do chamador.
    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            return "{\"_detail_error\":\"nao serializavel\"}";
        }
    }
}
