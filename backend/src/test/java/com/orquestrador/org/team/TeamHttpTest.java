package com.orquestrador.org.team;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Superfície HTTP de Times + cadeia de comando (T.2, ADR-0017). Estrutura organizacional é
 * GOVERNANÇA: mutações (criar time, arquiteto, mover agente, reparent) são BOARD-only (coerente
 * com o hire pós-R1); leitura é member (o canvas é da company). O gate do arquiteto aparece na
 * borda: wakeup de time sem arquiteto → 409 com mensagem acionável.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @Test
    void boardCriaTimeAtribuiAgenteDefineArquitetoEReparenta() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID lider = createAgent(companyId, board, "Atlas");
        UUID dev = createAgent(companyId, board, "Nix");
        String teamsUrl = "/api/companies/" + companyId + "/teams";

        // cria time SEM arquiteto (201) — trava é no dispatch, não na criação
        String created = mvc.perform(post(teamsUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"frontend\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dominio").value("Dev"))
                .andExpect(jsonPath("$.arquitetoAgentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID teamId = UUID.fromString(om.readTree(created).get("id").asText());

        // atribui o dev ao time (board-only, sub-recurso do agente)
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + dev + "/team")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId.toString()));

        // define o arquiteto (tem que ser membro; dev acabou de entrar)
        mvc.perform(patch(teamsUrl + "/" + teamId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + dev + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arquitetoAgentId").value(dev.toString()));

        // reparent: dev passa a reportar ao líder; e null vira raiz
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + dev + "/reports-to")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportsToId\":\"" + lider + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportsToId").value(lider.toString()));
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + dev + "/reports-to")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportsToId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportsToId").doesNotExist());

        // GET lista (member serve; board é member) com o time e o arquiteto
        mvc.perform(get(teamsUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].foco").value("frontend"));
    }

    @Test
    void mutacoesSaoBoardOnly_agenteAutenticadoLeva403EleELeituraPassa() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board, "Echo");
        String teamsUrl = "/api/companies/" + companyId + "/teams";

        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();

        // leitura é member: agente da company VÊ os times (o org é dado da company)
        mvc.perform(get(teamsUrl).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk());
        // mutações: agente 403 (estrutura organizacional é do board)
        mvc.perform(post(teamsUrl).header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + agentId + "/reports-to")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportsToId\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + agentId + "/team")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":null}"))
                .andExpect(status().isForbidden());
        // board sem membership → 403
        mvc.perform(post(teamsUrl).header("X-Board-User", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"y\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wakeupDeTimeSemArquitetoLeva409ComMensagemAcionavel() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board, "Echo");
        UUID teamId = createTeam(companyId, board, "Dev", "gate");

        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + agentId + "/team")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/wakeups")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"trabalhar\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reparentComCicloLeva409EHireValidaOReportsTo() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID a = createAgent(companyId, board, "A");
        UUID b = createAgent(companyId, board, "B");

        // B reporta a A; pendurar A debaixo de B fecharia ciclo → 409
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + b + "/reports-to")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportsToId\":\"" + a + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + a + "/reports-to")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportsToId\":\"" + b + "\"}"))
                .andExpect(status().isConflict());

        // o hire agora VALIDA o reportsToId (T.2 ligou a cadeia): id de fora → 404
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Z\",\"role\":\"executor\",\"reportsToId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Teams\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board, String nome) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + nome + "\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createTeam(UUID companyId, UUID board, String dominio, String foco) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"" + dominio + "\",\"foco\":\"" + foco + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
