package com.orquestrador.work.issue;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout atomico ponta-a-ponta sobre o stack HTTP real, incluindo auth de agente por Bearer
 * (chave mintada -> hash -> ActorResolutionFilter) e o isolamento cross-tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueCheckoutHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatRunRepository runs;

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID createCompany(UUID boardUser, String slug) throws Exception {
        String body = mvc.perform(post("/api/companies")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Produto\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    /**
     * Contratar EXIGE time (ADR-0045). Cada agente aqui disputa a Issue por conta própria, então
     * nasce no PRÓPRIO time e vira arquiteto dele: o dispatch segue destravado (ADR-0017) e nenhum
     * deles herda chefia do outro — exatamente o mundo que este teste já assumia.
     */
    private UUID createAgent(UUID boardUser, UUID companyId, String name) throws Exception {
        UUID teamId = createTeam(boardUser, companyId);
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"worker\",\"teamId\":\""
                                + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID agentId = idFrom(body);
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
        return agentId;
    }

    /** Foco aleatório porque (company, dominio, foco) é único e cada agente ganha o seu time. */
    private UUID createTeam(UUID boardUser, UUID companyId) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    private String mintKey(UUID boardUser, UUID companyId, UUID agentId) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", boardUser.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("apiKey").asText();
    }

    private UUID createIssue(UUID boardUser, UUID companyId, String title) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    /**
     * <b>Este teste afirmava, até a Fatia I-ter, o OPOSTO do
     * {@code LockOrfaoReaperTest.colheIssueCujoRunNaoTemLINHA_oPontoCegoDaRede}</b>, e os dois
     * conviviam verdes.
     *
     * <p>O estado era o mesmo — Issue {@code IN_PROGRESS} cujo lock nomeia um Run <b>sem linha</b>
     * em {@code heartbeat_run} — e os destinos eram incompatíveis: aqui {@code DONE} (o checkout
     * sem corpo fabricava o lock, e o {@code /complete} seguinte fechava a Issue), lá
     * {@code BLOCKED} (a rede colhe o órfão). Conviviam porque
     * {@code orquestrador.scheduling.enabled=false} no perfil de teste: a varredura nunca rodava
     * durante ESTE teste. Em produção, com o scheduler ligado, o mesmo fluxo perdia a corrida para
     * o reaper em ≤5 s — a Issue viva ia a {@code BLOCKED} e o {@code /complete} dava 409.
     *
     * <p><b>A reconciliação não foi escolher um vencedor: foi tirar o estado da mesa.</b> O
     * checkout agora exige um {@code runId} com procedência, então este teste <b>não consegue
     * mais</b> produzir o lock-sem-linha nem se quiser. O que ele passa a provar é o fluxo
     * legítimo: Run real → checkout → complete → {@code DONE}, e nesse fluxo o reaper deixa a
     * Issue em paz porque o Run <b>tem futuro</b>. O veredito do reaper sobre o lock-sem-linha
     * segue de pé, intocado, para o passivo acumulado e para os produtores que não passam por aqui.
     */
    @Test
    void checkoutAtomicoEGovernancaViaHttp() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board, "co-" + rand());
        UUID a1 = createAgent(board, companyId, "A1");
        UUID a2 = createAgent(board, companyId, "A2");
        String keyA1 = mintKey(board, companyId, a1);
        String keyA2 = mintKey(board, companyId, a2);
        UUID issueId = createIssue(board, companyId, "tarefa");

        String checkoutUrl = "/api/companies/" + companyId + "/issues/" + issueId + "/checkout";
        UUID runId = runReal(companyId, a1, issueId);

        // A1 pega com o PRÓPRIO run -> 200, IN_PROGRESS, assignee A1
        mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.issue.assigneeAgentId").value(a1.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()));

        // A2 tenta com o run DELE -> 409 (zero double-work). Com o run dele, e não com um UUID
        // qualquer: senão o 409 viraria 403 e o teste deixaria de provar a arbitragem do banco.
        mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runReal(companyId, a2, issueId) + "\"}"))
                .andExpect(status().isConflict());

        // board nao faz checkout (so agentes) -> 403
        mvc.perform(post(checkoutUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());

        // agente de OUTRA company -> 403 (cross-tenant, barrado no assertMember antes do service)
        UUID board2 = UUID.randomUUID();
        UUID company2 = createCompany(board2, "co2-" + rand());
        UUID a3 = createAgent(board2, company2, "A3");
        String keyA3 = mintKey(board2, company2, a3);
        mvc.perform(post(checkoutUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());

        // A1 conclui com o runId detentor -> 200 DONE
        mvc.perform(post("/api/companies/" + companyId + "/issues/" + issueId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    /** Um Run REAL do motor — sem a linha, o checkout recusa por falta de procedência. */
    private UUID runReal(UUID companyId, UUID agentId, UUID issueId) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId,
                RunStatus.RUNNING)).getId();
    }
}
