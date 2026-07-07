package com.orquestrador.board;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F.0: o feed de atividade do Mission Control sobre o activity_log (ADR-0009), mais recente
 * primeiro, limit clampado. O detail já é JSON no banco (só IDs/enums/números) e sai CRU —
 * o front lê campos direto, sem parse de string escapada. Board-only.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/activity")
public class ActivityController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ActivityLogRepository activity;
    private final CompanyAccessGuard guard;

    public ActivityController(ActivityLogRepository activity, CompanyAccessGuard guard) {
        this.activity = activity;
        this.guard = guard;
    }

    @GetMapping
    public List<ActivityEntry> feed(@PathVariable UUID companyId,
                                    @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        guard.assertBoardMember(companyId);
        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);
        return activity.findByCompanyIdOrderByCreatedAtDesc(companyId, PageRequest.of(0, safeLimit))
                .stream()
                .map(ActivityEntry::from)
                .toList();
    }

    public record ActivityEntry(UUID id, String actorType, UUID actorId, String action,
                                String entityType, UUID entityId,
                                @JsonRawValue String detail, Instant createdAt) {
        static ActivityEntry from(ActivityLog entry) {
            return new ActivityEntry(entry.getId(), entry.getActorType(), entry.getActorId(),
                    entry.getAction(), entry.getEntityType(), entry.getEntityId(),
                    entry.getDetail(), entry.getCreatedAt());
        }
    }
}
