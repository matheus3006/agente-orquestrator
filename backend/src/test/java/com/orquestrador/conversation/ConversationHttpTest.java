package com.orquestrador.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.execution.HeartbeatDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A superfície HTTP da conversa (T.3, board-only): abrir chat, postar mensagem (202 — o turno é
 * assíncrono), ler a thread. Deny-by-default: agente autenticado NUNCA passa (a conversa é
 * material do operador; o CEO participa por dentro do motor, não por HTTP).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private HeartbeatDispatcher dispatcher;

    /** O post de mensagem enfileira sem despachar; não deixar QUEUED pros vizinhos de contexto. */
    @AfterEach
    void drenaFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void fluxoBoardOnlyDaConversa() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);

        UUID companyId = idFrom(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        UUID ceoId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atlas\",\"role\":\"ceo\",\"adapterType\":\"process\","
                                + "\"runtimeCommand\":\"printf '%s' \\\"$ORQ_PROMPT\\\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String base = "/api/companies/" + companyId + "/conversations";

        // Board abre o chat
        UUID conversationId = idFrom(mvc.perform(post(base)
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Geral\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subject").value("Geral"))
                .andReturn().getResponse().getContentAsString());

        // Agente com chave NÃO acessa a conversa (board-only)
        String key = om.readTree(mvc.perform(post("/api/companies/" + companyId + "/agents/" + ceoId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        mvc.perform(get(base).header("Authorization", "Bearer " + key))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"oi\"}"))
                .andExpect(status().isForbidden());

        // Outro board sem membership → 403
        mvc.perform(get(base).header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        // Board posta mensagem → 202 (turno assíncrono) com turnId + runId
        mvc.perform(post(base + "/" + conversationId + "/messages")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Montar o time de marketing\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.turnId").exists())
                .andExpect(jsonPath("$.runId").exists());

        // Thread mostra o turno PENDING com a mensagem do dono
        mvc.perform(get(base + "/" + conversationId + "/turns").header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerMessage").value("Montar o time de marketing"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        // Lista de chats
        mvc.perform(get(base).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subject").value("Geral"));

        // Validação: assunto em branco → 422; mensagem-bomba → 422; assunto duplicado → 409
        mvc.perform(post(base).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(base + "/" + conversationId + "/messages")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "x".repeat(9000) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(base).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Geral\"}"))
                .andExpect(status().isConflict());
    }
}
