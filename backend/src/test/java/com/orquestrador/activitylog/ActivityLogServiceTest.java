package com.orquestrador.activitylog;

import tools.jackson.databind.ObjectMapper;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ActivityLogService grava eventos append-only com detail JSON (só IDs/enums). */
@SpringBootTest
@ActiveProfiles("test")
class ActivityLogServiceTest {

    @Autowired
    private ActivityLogService activityLog;
    @Autowired
    private ActivityLogRepository repository;
    @Autowired
    private CompanyService companies;
    @Autowired
    private ObjectMapper om;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void gravaEventoComDetailJson() throws Exception {
        UUID companyId = newCompany();
        UUID board = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();

        activityLog.record(companyId, ActorType.BOARD, board, ActivityAction.SKILL_INSTALLED,
                "company_skill", skillId, Map.of("version", 1));

        var events = repository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        assertThat(events).hasSize(1);
        ActivityLog e = events.get(0);
        assertThat(e.getAction()).isEqualTo("SKILL_INSTALLED");
        assertThat(e.getActorType()).isEqualTo("BOARD");
        assertThat(e.getActorId()).isEqualTo(board);
        assertThat(e.getEntityId()).isEqualTo(skillId);
        // detail é JSON e carrega o dado tipado
        assertThat(om.readTree(e.getDetail()).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void detailVazioFicaNulo() {
        UUID companyId = newCompany();
        activityLog.record(companyId, ActorType.BOARD, UUID.randomUUID(), ActivityAction.SKILL_ATTACHED,
                "issue_skill_attachment", UUID.randomUUID(), Map.of());

        ActivityLog e = repository.findByCompanyIdOrderByCreatedAtDesc(companyId).get(0);
        assertThat(e.getDetail()).isNull();
    }
}
