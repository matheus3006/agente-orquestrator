package com.orquestrador.org.team;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    void boardCriaTimeAtribuiAgenteDefineArquitetoEAChefiaDerivaDoGestor() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
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

        // os dois já NASCEM no time: contratar exige time (ADR-0045)
        UUID lider = createAgent(companyId, board, "Atlas", teamId);
        UUID dev = createAgent(companyId, board, "Nix", teamId);

        // o sub-recurso de pertencimento segue board-only e continua respondendo o time do agente
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

        // A CHEFIA DERIVA (ADR-0045): o PATCH /reports-to morreu, então quem move o chefe do dev é
        // o posto de GESTOR do time — designar Atlas reescreve o espelho no MESMO ato.
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + lider + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gestorAgentId").value(lider.toString()));
        assertThat(reportsToDe(companyId, board, dev)).isEqualTo(lider.toString());
        // o próprio gestor não reporta a si mesmo: sobe, e acima deste time só existe a raiz
        assertThat(reportsToDe(companyId, board, lider)).isNull();

        // limpar o posto devolve o dev pra raiz — sem gestor no caminho, sem chefe
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gestorAgentId").doesNotExist());
        assertThat(reportsToDe(companyId, board, dev)).isNull();

        // GET lista (member serve; board é member) com o time e o arquiteto
        mvc.perform(get(teamsUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].foco").value("frontend"));
    }

    @Test
    void mutacoesSaoBoardOnly_agenteAutenticadoLeva403EleELeituraPassa() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID teamId = createTeam(companyId, board, "Dev", "gate");
        UUID agentId = createAgent(companyId, board, "Echo", teamId);
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
        // os dois postos da árvore (ADR-0045) entraram no lugar do finado PATCH /reports-to
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":null}"))
                .andExpect(status().isForbidden());
        // promover (ADR-0046) é a MESMA governança board-only — ato soberano do dono
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor/promover")
                        .header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());
        mvc.perform(patch(teamsUrl + "/" + teamId + "/parent")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentTeamId\":null}"))
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
        // o agente nasce JÁ no time e o time nasce SEM arquiteto — é essa combinação que trava
        UUID teamId = createTeam(companyId, board, "Dev", "gate");
        UUID agentId = createAgent(companyId, board, "Echo", teamId);

        mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/wakeups")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"trabalhar\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * O ciclo saiu da cadeia de agentes e passou para a ÁRVORE DE TIMES (ADR-0045) — é lá que o
     * organograma vive agora. E o hire, que antes validava o {@code reportsToId}, hoje valida o
     * TIME: sem time é 422 na porta, com time de fora é 404.
     */
    @Test
    void reparentComCicloLeva409EHireValidaOTime() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String teamsUrl = "/api/companies/" + companyId + "/teams";
        UUID pai = createTeam(companyId, board, "Dev", "pai");
        UUID filho = createTeam(companyId, board, "Dev", "filho");

        // filho pendura no pai; pendurar o PAI debaixo do filho fecharia o ciclo → 409
        mvc.perform(patch(teamsUrl + "/" + filho + "/parent")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentTeamId\":\"" + pai + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentTeamId").value(pai.toString()));
        mvc.perform(patch(teamsUrl + "/" + pai + "/parent")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentTeamId\":\"" + filho + "\"}"))
                .andExpect(status().isConflict());

        // hire SEM time não passa nem do validador — todo contratado pertence a um time
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Z\",\"role\":\"executor\"}"))
                .andExpect(status().isUnprocessableEntity());

        // e time que não é desta company → 404 (o hire valida o time, como validava o chefe)
        mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Z\",\"role\":\"executor\",\"teamId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * ADR-0046 (D-ORG-2): designar nasce ESTAGIO; promover grava PROMOVIDO + quem/quando; promover
     * de novo é 409 (só ESTAGIO→PROMOVIDO); o ATO fica no activity_log da company (ADR-0009).
     */
    @Test
    void promoverGestorViaHttp_estagioParaPromovidoComRastroEAtividade() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String teamsUrl = "/api/companies/" + companyId + "/teams";
        UUID teamId = createTeam(companyId, board, "Marketing", "growth");
        UUID gestorId = createAgent(companyId, board, "Ana", teamId);

        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + gestorId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gestorStatus").value("ESTAGIO"))
                .andExpect(jsonPath("$.gestorPromovidoEm").doesNotExist());

        String promovido = mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor/promover")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gestorStatus").value("PROMOVIDO"))
                .andExpect(jsonPath("$.gestorPromovidoPorActorType").value("BOARD"))
                .andExpect(jsonPath("$.gestorPromovidoPorActorId").value(board.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(om.readTree(promovido).get("gestorPromovidoEm").asText()).isNotEmpty();

        // transição só ESTAGIO→PROMOVIDO — repromover é 409, nunca um segundo carimbo
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor/promover")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isConflict());

        JsonNode feed = om.readTree(mvc.perform(get("/api/companies/" + companyId + "/activity")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode evento = null;
        for (JsonNode candidato : feed) {
            if ("GESTOR_PROMOVIDO".equals(candidato.get("action").asText())) {
                evento = candidato;
                break;
            }
        }
        assertThat(evento).as("GESTOR_PROMOVIDO no activity_log da company").isNotNull();
        assertThat(evento.get("actorType").asText()).isEqualTo("BOARD");
        assertThat(evento.get("actorId").asText()).isEqualTo(board.toString());
        assertThat(evento.get("entityType").asText()).isEqualTo("team");
        assertThat(evento.get("entityId").asText()).isEqualTo(teamId.toString());
        assertThat(evento.get("detail").get("gestorAgentId").asText()).isEqualTo(gestorId.toString());
    }

    @Test
    void promoverSemGestorDesignado_ehConflitoAcionavel() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID teamId = createTeam(companyId, board, "Marketing", "conteudo");

        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/gestor/promover")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isConflict());
    }

    /**
     * Regressão explícita (F-ORG-A já provou isso; ADR-0046 não pode quebrar): promover o gestor
     * NÃO destrava o dispatch — só o arquiteto trava/destrava (D-ORG-A.1, dois postos separados).
     */
    @Test
    void promoverGestorNaoDestravaODispatch_continuaSoDoArquiteto() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        String teamsUrl = "/api/companies/" + companyId + "/teams";
        UUID teamId = createTeam(companyId, board, "Marketing", "growth");
        UUID gestorId = createAgent(companyId, board, "Ana", teamId);

        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + gestorId + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch(teamsUrl + "/" + teamId + "/gestor/promover")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gestorStatus").value("PROMOVIDO"));

        // gestor promovido, time AINDA sem arquiteto → wakeup continua 409
        mvc.perform(post("/api/companies/" + companyId + "/agents/" + gestorId + "/wakeups")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"trabalhar\"}"))
                .andExpect(status().isConflict());
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Teams\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** O time é parâmetro porque contratar EXIGE time (ADR-0045) — e aqui ele é o próprio assunto. */
    private UUID createAgent(UUID companyId, UUID board, String nome, UUID teamId) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + nome + "\",\"role\":\"worker\",\"teamId\":\""
                                + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A chefia é ESPELHO: não existe endpoint pra editá-la, então ela se lê no roster. */
    private String reportsToDe(UUID companyId, UUID board, UUID agentId) throws Exception {
        String roster = mvc.perform(get("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode agente : om.readTree(roster)) {
            if (agentId.toString().equals(agente.get("id").asText())) {
                JsonNode chefe = agente.get("reportsToId");
                return chefe == null || chefe.isNull() ? null : chefe.asText();
            }
        }
        throw new AssertionError("agente " + agentId + " sumiu do roster da company");
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
