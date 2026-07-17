package com.orquestrador.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F.9 (ADR-0021): o serving board-only do preview. A lista da Issue devolve METADADOS (sem
 * content — o conteudo pesa; a tela busca por id sob demanda). O conteudo NUNCA e HTML navegavel:
 * {@code text/plain; charset=utf-8} + {@code nosniff} — quem renderiza e o PreviewFrame (srcdoc
 * dentro de sandbox), via fetcher autenticado. Imutavel por id → cache infinito no front.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreviewArtifactHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private PreviewArtifactRepository previews;

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
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID issueId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"prototipo\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Cenario(board, companyId, agentId, issueId);
    }

    @Test
    void listaDeVersoesDaIssueEMetadadosSemContent() throws Exception {
        Cenario cenario = cenario();
        previews.save(new PreviewArtifact(UUID.randomUUID(), cenario.companyId(), cenario.issueId(),
                cenario.agentId(), UUID.randomUUID(), 1, "<html>v1</html>", "hash-1"));
        PreviewArtifact v2 = previews.save(new PreviewArtifact(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(), 2, "<html>v2</html>", "hash-2"));

        String url = "/api/companies/" + cenario.companyId() + "/issues/" + cenario.issueId()
                + "/preview-artifacts";
        JsonNode versoes = om.readTree(mvc.perform(get(url).header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(versoes).hasSize(2);
        // da mais nova para a mais antiga (a tela abre na ultima)
        assertThat(versoes.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(versoes.get(0).get("id").asText()).isEqualTo(v2.getId().toString());
        assertThat(versoes.get(0).get("runId").asText()).isEqualTo(v2.getRunId().toString());
        assertThat(versoes.get(0).get("sizeBytes").asInt()).isEqualTo("<html>v2</html>".getBytes().length);
        assertThat(versoes.get(0).has("content")).isFalse();

        // board-only: agente 403, cross-tenant 403
        String key = om.readTree(mvc.perform(post("/api/companies/" + cenario.companyId() + "/agents/"
                        + cenario.agentId() + "/api-keys")
                        .header("X-Board-User", cenario.board().toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(url).header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        // Issue inexistente/alheia = 404 (padrao da casa: "Issue company-led -> fora da company e 404"),
        // nunca 200 [] indistinguivel de "issue sem preview".
        mvc.perform(get("/api/companies/" + cenario.companyId() + "/issues/" + UUID.randomUUID()
                        + "/preview-artifacts")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void conteudoSaiComoTextoNaoNavegavelComNosniff() throws Exception {
        Cenario cenario = cenario();
        PreviewArtifact artefato = previews.save(new PreviewArtifact(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(), 1,
                "<!doctype html><h1>olá</h1>", "hash-1"));

        String url = "/api/companies/" + cenario.companyId() + "/preview-artifacts/" + artefato.getId()
                + "/content";
        MvcResult resposta = mvc.perform(get(url).header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isOk()).andReturn();

        // NUNCA text/html navegavel: texto + nosniff (o render acontece SO no PreviewFrame)
        assertThat(resposta.getResponse().getContentType()).startsWith("text/plain");
        assertThat(resposta.getResponse().getContentType()).containsIgnoringCase("charset=utf-8");
        assertThat(resposta.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        // imutavel por id -> o front pode cachear para sempre
        assertThat(resposta.getResponse().getHeader("Cache-Control")).contains("immutable");
        assertThat(resposta.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("<!doctype html><h1>olá</h1>");

        // board-only + escopo por company: outro tenant nao alcanca nem por id direto
        mvc.perform(get(url).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/companies/" + cenario.companyId() + "/preview-artifacts/"
                        + UUID.randomUUID() + "/content")
                        .header("X-Board-User", cenario.board().toString()))
                .andExpect(status().isNotFound());
    }
}
