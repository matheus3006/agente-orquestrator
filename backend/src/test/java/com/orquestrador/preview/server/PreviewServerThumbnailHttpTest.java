package com.orquestrador.preview.server;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.13 (ADR-0027 §B7): o serving board-only do thumbnail v2. A lista da Issue devolve METADADOS (sem
 * os bytes — o PNG pesa; a tela busca por id sob demanda). Um PNG e INERTE: o conteudo sai como
 * {@code image/png} + {@code nosniff}, direto num {@code <img>} (sem sandbox/iframe, ao contrario do
 * HTML da foto v1). Imutavel por id -> cache infinito no front.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviewServerThumbnailHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private PreviewServerThumbnailRepository thumbnails;

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private record Cenario(UUID board, UUID companyId, UUID agentId, UUID issueId) {
    }

    private Cenario cenario() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID agentId = createAgent(companyId, board);
        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"app v2\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Cenario(board, companyId, agentId, issueId);
    }

    /**
     * Contratar EXIGE time (ADR-0045). Aqui o agente e so o dono da foto, entao ele nasce no
     * PROPRIO time e vira arquiteto dele: o dispatch segue destravado (ADR-0017) e a chefia
     * derivada continua nula — exatamente o mundo que este teste ja assumia.
     */
    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        UUID teamId = createTeam(companyId, board);
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\",\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
        return agentId;
    }

    /** Foco aleatorio porque (company, dominio, foco) e unico e cada agente ganha o seu time. */
    private UUID createTeam(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static byte[] fakePng() {
        byte[] png = new byte[512];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        return png;
    }

    @Test
    void listaDeVersoesDaIssueEMetadadosSemOsBytes() throws Exception {
        Cenario cenario = cenario();
        thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(), fakePng(), "hash-1"));
        PreviewServerThumbnail nova = thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(),
                cenario.companyId(), cenario.issueId(), cenario.agentId(), UUID.randomUUID(),
                fakePng(), "hash-2"));

        String url = "/api/companies/" + cenario.companyId() + "/issues/" + cenario.issueId()
                + "/preview-server-thumbnails";
        JsonNode versoes = om.readTree(mvc.perform(get(url)
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(versoes).hasSize(2);
        // da mais nova para a mais antiga (a tela mostra a ultima)
        assertThat(versoes.get(0).get("id").asText()).isEqualTo(nova.getId().toString());
        assertThat(versoes.get(0).get("contentHash").asText()).isEqualTo("hash-2");
        assertThat(versoes.get(0).get("sizeBytes").asInt()).isEqualTo(512);
        assertThat(versoes.get(0).has("content")).as("os bytes nao viajam na lista").isFalse();

        // board-only: agente 403, cross-tenant 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + cenario.companyId() + "/agents/"
                        + cenario.agentId() + "/api-keys")
                        .header("X-Board-User", cenario.board().toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(url).header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        // Issue alheia = 404, nunca 200 [] indistinguivel de "issue sem thumbnail"
        mvc.perform(get("/api/companies/" + cenario.companyId() + "/issues/" + UUID.randomUUID()
                        + "/preview-server-thumbnails")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void conteudoSaiComoImagemPngInerteComNosniffECacheImutavel() throws Exception {
        Cenario cenario = cenario();
        byte[] png = fakePng();
        PreviewServerThumbnail thumbnail = thumbnails.save(new PreviewServerThumbnail(UUID.randomUUID(),
                cenario.companyId(), cenario.issueId(), cenario.agentId(), UUID.randomUUID(),
                png, "hash-1"));

        String url = "/api/companies/" + cenario.companyId() + "/preview-server-thumbnails/"
                + thumbnail.getId() + "/content";
        MvcResult resposta = mvc.perform(get(url).header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn();

        assertThat(resposta.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resposta.getResponse().getHeader("Cache-Control")).contains("immutable");
        assertThat(resposta.getResponse().getContentAsByteArray()).isEqualTo(png);

        // board-only + escopo por company: outro tenant nao alcanca nem por id direto
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/companies/" + cenario.companyId() + "/preview-server-thumbnails/"
                        + UUID.randomUUID() + "/content")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isNotFound());
    }
}
