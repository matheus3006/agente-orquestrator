package com.orquestrador.preview.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.10 (ADR-0023 §7): a superficie board-only do servidor de preview — start/stop/heartbeat por
 * Issue (botao "Rodar app" e keepalive), status para a Issue/Review (manifest + vivo), e a tela
 * Servidores (vivos + candidatas). O iframe fala DIRETO com a porta do app (origin proprio);
 * estas rotas so orquestram o ciclo de vida.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviewServerHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private PreviewServerSupervisor supervisor;

    private record Cenario(UUID board, UUID companyId, UUID agentId, UUID issueId) {
    }

    private final java.util.List<Cenario> cenarios = new java.util.ArrayList<>();

    @AfterEach
    void derrubaTudo() {
        cenarios.forEach(cenario ->
                supervisor.stopFor(cenario.companyId(), cenario.issueId(), null));
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private Cenario cenario() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"app com servidor\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Cenario cenario = new Cenario(board, companyId, agentId, issueId);
        cenarios.add(cenario);
        return cenario;
    }

    private int portaLivre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private UUID declaraManifest(Cenario cenario, int porta) {
        return manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(),
                "python3 -m http.server " + porta, porta)).getRunId();
    }

    @Test
    void cicloDeVidaInteiroPelaApiStartStatusHeartbeatStop() throws Exception {
        Cenario cenario = cenario();
        int porta = portaLivre();
        UUID runDeclarante = declaraManifest(cenario, porta);
        String base = "/api/companies/" + cenario.companyId() + "/issues/" + cenario.issueId()
                + "/preview-server";

        JsonNode started = om.readTree(mvc.perform(post(base + "/start")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(started.get("started").asBoolean()).isTrue();
        assertThat(started.get("url").asText()).isEqualTo("http://127.0.0.1:" + porta);

        JsonNode statusResposta = om.readTree(mvc.perform(get(base)
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(statusResposta.get("manifest").get("runId").asText())
                .isEqualTo(runDeclarante.toString());
        assertThat(statusResposta.get("manifest").get("command").asText())
                .isEqualTo("python3 -m http.server " + porta);
        assertThat(statusResposta.get("live").get("url").asText())
                .isEqualTo("http://127.0.0.1:" + porta);
        assertThat(statusResposta.get("live").get("ttlRemainingSeconds").asLong()).isGreaterThan(0);

        JsonNode heartbeat = om.readTree(mvc.perform(post(base + "/heartbeat")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(heartbeat.get("live").asBoolean()).isTrue();

        JsonNode stopped = om.readTree(mvc.perform(post(base + "/stop")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stopped.get("stopped").asBoolean()).isTrue();

        JsonNode aposStop = om.readTree(mvc.perform(get(base)
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aposStop.get("live").isNull()).isTrue();
        assertThat(aposStop.get("manifest").isNull())
                .as("a declaracao persiste alem da vida do processo")
                .isFalse();
    }

    @Test
    void startSemManifestERespostaLegivelNaoExcecao() throws Exception {
        Cenario cenario = cenario();

        JsonNode resposta = om.readTree(mvc.perform(post("/api/companies/" + cenario.companyId()
                        + "/issues/" + cenario.issueId() + "/preview-server/start")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(resposta.get("started").asBoolean()).isFalse();
        assertThat(resposta.get("error").asText()).contains("nenhum servidor declarado");
    }

    @Test
    void telaServidoresListaVivosECandidatas() throws Exception {
        Cenario cenario = cenario();
        int porta = portaLivre();
        declaraManifest(cenario, porta);
        mvc.perform(post("/api/companies/" + cenario.companyId() + "/issues/" + cenario.issueId()
                        + "/preview-server/start")
                .header("X-Board-User", cenario.board().toString())).andExpect(status().isOk());

        JsonNode tela = om.readTree(mvc.perform(get("/api/companies/" + cenario.companyId()
                        + "/preview-servers")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(tela.get("live")).hasSize(1);
        JsonNode vivo = tela.get("live").get(0);
        assertThat(vivo.get("issueId").asText()).isEqualTo(cenario.issueId().toString());
        // #N na tela Servidores (ADR-0033): a issue do cenario e a 1a da company
        assertThat(vivo.get("issueSeq").asInt()).isEqualTo(1);
        assertThat(vivo.get("issueTitle").asText()).isEqualTo("app com servidor");
        assertThat(vivo.get("agentName").asText()).isEqualTo("A");
        assertThat(vivo.get("command").asText()).isEqualTo("python3 -m http.server " + porta);
        assertThat(vivo.get("url").asText()).isEqualTo("http://127.0.0.1:" + porta);
        assertThat(vivo.get("ttlRemainingSeconds").asLong()).isGreaterThan(0);

        assertThat(tela.get("candidates")).hasSize(1);
        JsonNode candidata = tela.get("candidates").get(0);
        assertThat(candidata.get("issueId").asText()).isEqualTo(cenario.issueId().toString());
        assertThat(candidata.get("issueSeq").asInt()).isEqualTo(1);
        assertThat(candidata.get("command").asText()).isEqualTo("python3 -m http.server " + porta);
        assertThat(candidata.get("live").asBoolean()).isTrue();
    }

    @Test
    void issueDeOutraCompanyE404NuncaVazamento() throws Exception {
        Cenario dona = cenario();
        Cenario intrusa = cenario();

        mvc.perform(post("/api/companies/" + intrusa.companyId() + "/issues/" + dona.issueId()
                        + "/preview-server/start")
                        .header("X-Board-User", intrusa.board().toString()))
                .andExpect(status().isNotFound());
    }
}
