package com.orquestrador.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-N4 (ADR-0040, D4): o transporte SSE do transcript (substitui/complementa o poll de 2s de
 * {@link RunStepsController#steps}). Reusa a MESMA paginacao — so muda o transporte: um push por
 * conexao em vez do cliente repetir o offset a cada 2s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunStepsStreamHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunStore store;

    @Test
    void isTerminalClassificaOsSeisStatus() {
        assertThat(RunStepsController.isTerminal(RunStatus.SUCCEEDED)).isTrue();
        assertThat(RunStepsController.isTerminal(RunStatus.FAILED)).isTrue();
        assertThat(RunStepsController.isTerminal(RunStatus.CANCELLED)).isTrue();
        assertThat(RunStepsController.isTerminal(RunStatus.INTERRUPTED)).isTrue();
        assertThat(RunStepsController.isTerminal(RunStatus.QUEUED)).isFalse();
        assertThat(RunStepsController.isTerminal(RunStatus.RUNNING)).isFalse();
        assertThat(RunStepsController.isTerminal(RunStatus.RECOVERING)).isFalse();
    }

    @Test
    void runJaTerminadoEnviaOBacklogEFechaImediatamente() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));
        store.recordStep(runId, "STDOUT", "linha-1");
        store.recordStep(runId, "STDOUT", "linha-2");
        store.finishSuccess(runId, "worker-test");

        MvcResult iniciado = mvc.perform(get(streamUrl(companyId, runId)).header("X-Board-User", board.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();
        iniciado.getAsyncResult(5000);
        String corpo = mvc.perform(asyncDispatch(iniciado))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).contains("linha-1").contains("linha-2").contains("SUCCEEDED");
    }

    /**
     * F-N4: achado do VENDO — uma run sem STDOUT novo por um tempo fica com a conexao ZERO
     * BYTES por ciclo de poll; o rewrite dev do Next.js (e qualquer proxy/LB com timeout de
     * socket ocioso) derruba isso como conexao morta (ECONNRESET em produção real). O keep-alive
     * (comentario SSE) tem que aparecer no corpo mesmo num trecho sem nenhum step novo.
     */
    @Test
    void keepAliveApareceQuandoNenhumStepNovoChegaNoCiclo() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));

        MvcResult iniciado = mvc.perform(get(streamUrl(companyId, runId)).header("X-Board-User", board.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Nenhum step novo por 3+ ciclos de poll (500ms cada) — silencio deliberado antes do
        // finalize, para o keep-alive ter que aparecer no meio do caminho.
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            store.finishSuccess(runId, "worker-test");
        });

        iniciado.getAsyncResult(5000);
        String corpo = mvc.perform(asyncDispatch(iniciado))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).contains(":keep-alive").contains("SUCCEEDED");
    }

    @Test
    void novoStepChegaAoVivoEOStreamFechaQuandoOStatusViraTerminal() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));
        store.recordStep(runId, "STDOUT", "linha-antes-de-conectar");

        MvcResult iniciado = mvc.perform(get(streamUrl(companyId, runId)).header("X-Board-User", board.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        // o run ainda esta RUNNING quando o cliente conecta; o step novo e o finalize chegam
        // DEPOIS, numa thread separada — o poll de fundo tem que os pegar sem o cliente reconectar.
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            store.recordStep(runId, "STDOUT", "linha-ao-vivo");
            store.finishSuccess(runId, "worker-test");
        });

        iniciado.getAsyncResult(5000);
        String corpo = mvc.perform(asyncDispatch(iniciado))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).contains("linha-antes-de-conectar").contains("linha-ao-vivo").contains("SUCCEEDED");
    }

    /**
     * O guard roda SINCRONO, antes de qualquer processamento async ser iniciado (a exceção do
     * {@code assertBoardMember} interrompe o metodo ANTES do {@code SseEmitter} ser construido) —
     * a resposta de erro e uma resposta HTTP comum, sem `asyncStarted()`. Prova que a proteção
     * real do endpoint independe do falso-positivo do tripwire (ver comentario em
     * {@link RunStepsController#stream}): um ator sem acesso e barrado ANTES do stream nascer.
     */
    @Test
    void streamForaDaCompanyOuInexistenteDa404EBoardOnly403() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(companyId, board);
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null,
                RunStatus.QUEUED)).getId();
        store.claim(runId, "worker-test", Duration.ofMinutes(5));

        UUID outroBoard = UUID.randomUUID();
        UUID outraCompany = createCompany(outroBoard);
        mvc.perform(get(streamUrl(outraCompany, runId)).header("X-Board-User", outroBoard.toString()))
                .andExpect(status().isNotFound());

        mvc.perform(get(streamUrl(companyId, UUID.randomUUID())).header("X-Board-User", board.toString()))
                .andExpect(status().isNotFound());

        String apiKey = om.readTree(mvc.perform(
                        post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                                .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(streamUrl(companyId, runId)).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isForbidden());

        mvc.perform(get(streamUrl(companyId, runId)).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    private String streamUrl(UUID companyId, UUID runId) {
        return "/api/companies/" + companyId + "/runs/" + runId + "/steps/stream";
    }

    private UUID createCompany(UUID board) throws Exception {
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        return idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stream\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createAgent(UUID companyId, UUID board) throws Exception {
        return idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"role\":\"worker\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }
}
