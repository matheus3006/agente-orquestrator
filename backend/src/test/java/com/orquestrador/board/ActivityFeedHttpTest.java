package com.orquestrador.board;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.0 (frontend fechado, Q3): GET /activity?limit= — o feed do Mission Control sobre o
 * activity_log (ADR-0009), mais recente primeiro, limit clampado. O detail (jsonb só com
 * IDs/enums/números) sai como JSON estruturado, não como string escapada. Board-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityFeedHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ActivityLogService activityLog;

    @Test
    void feedMaisRecentePrimeiroComLimitClampadoEDetailEstruturado() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID issueId = UUID.randomUUID();

        activityLog.record(companyId, ActorType.BOARD, board, ActivityAction.SKILL_IMPORTED,
                "company", companyId, Map.of("created", 2, "updated", 0));
        Thread.sleep(2); // separa os created_at (ordem do feed)
        activityLog.record(companyId, ActorType.AGENT, UUID.randomUUID(), ActivityAction.ISSUE_BLOCKED,
                "issue", issueId, Map.of("run_id", UUID.randomUUID().toString()));
        Thread.sleep(2);
        activityLog.record(companyId, ActorType.BOARD, board, ActivityAction.ISSUE_REOPENED,
                "issue", issueId, null);

        String url = "/api/companies/" + companyId + "/activity";
        JsonNode feed = getFeed(url, board);
        assertThat(feed).hasSize(3);
        assertThat(feed.get(0).get("action").asText()).isEqualTo("ISSUE_REOPENED");
        assertThat(feed.get(1).get("action").asText()).isEqualTo("ISSUE_BLOCKED");
        assertThat(feed.get(2).get("action").asText()).isEqualTo("SKILL_IMPORTED");

        JsonNode reopened = feed.get(0);
        assertThat(reopened.get("actorType").asText()).isEqualTo("BOARD");
        assertThat(reopened.get("actorId").asText()).isEqualTo(board.toString());
        assertThat(reopened.get("entityType").asText()).isEqualTo("issue");
        assertThat(reopened.get("entityId").asText()).isEqualTo(issueId.toString());
        assertThat(reopened.get("detail").isNull()).isTrue();
        assertThat(reopened.get("createdAt").asText()).isNotEmpty();

        // detail é JSON estruturado (objeto), não string escapada — o front lê campos direto
        JsonNode imported = feed.get(2);
        assertThat(imported.get("detail").get("created").asInt()).isEqualTo(2);

        // limit respeitado (os N mais recentes) e clampado nas bordas — nunca 500
        JsonNode doisMaisRecentes = getFeed(url + "?limit=2", board);
        assertThat(doisMaisRecentes).hasSize(2);
        assertThat(doisMaisRecentes.get(0).get("action").asText()).isEqualTo("ISSUE_REOPENED");
        assertThat(doisMaisRecentes.get(1).get("action").asText()).isEqualTo("ISSUE_BLOCKED");
        JsonNode clampMinimo = getFeed(url + "?limit=0", board);
        assertThat(clampMinimo).hasSize(1);
        JsonNode clampMaximo = getFeed(url + "?limit=99999", board);
        assertThat(clampMaximo).hasSize(3);
    }

    @Test
    void feedEhBoardOnly() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String url = "/api/companies/" + companyId + "/activity";

        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        mvc.perform(get(url).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private JsonNode getFeed(String url, UUID board) throws Exception {
        return om.readTree(mvc.perform(get(url).header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Feed\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
