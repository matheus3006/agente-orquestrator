package com.orquestrador.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verbo/《Content-Type》/《Accept》 errados em rota que EXISTE respondem 405/415/406 no formato da
 * casa, e não o 500 que o handler genérico produzia. Irmão do {@code NoRouteHttpTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MethodNotAllowedHttpTest {

    @Autowired
    private MockMvc mvc;

    /** O caso medido ao vivo pelo usuário: DELETE numa rota que só faz GET devolvia 500. */
    @Test
    void metodoErradoEmRotaRealResponde405() throws Exception {
        mvc.perform(delete("/actuator/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andExpect(jsonPath("$.message").value("Metodo nao suportado: DELETE /actuator/health"));
    }

    /**
     * O header {@code Allow} é obrigatório num 405 (RFC 9110 §15.5.6). Ao interceptar a exceção
     * nós tiramos o handler default do Spring do caminho — este teste é o que impede a regressão
     * silenciosa de responder 405 sem dizer qual verbo vale.
     */
    @Test
    void respostaTrazAllowComOsVerbosAceitos() throws Exception {
        mvc.perform(delete("/api/companies/" + UUID.randomUUID() + "/teams"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Allow"))
                        .contains("GET").contains("POST"));
    }

    /** Fixa a exceção concreta: se o Spring trocar de tipo, o teste conta o porquê. */
    @Test
    void aExcecaoEhHttpRequestMethodNotSupported() throws Exception {
        MvcResult result = mvc.perform(delete("/actuator/health")).andReturn();

        assertThat(result.getResolvedException())
                .isInstanceOf(org.springframework.web.HttpRequestMethodNotSupportedException.class);
    }

    /**
     * 415: POST com {@code text/plain} numa rota que só lê JSON. A exceção nasce na resolução do
     * argumento, ANTES do corpo do controller — por isso o guard de company nunca roda e o teste
     * não precisa de header de auth.
     */
    @Test
    void contentTypeNaoSuportadoResponde415() throws Exception {
        mvc.perform(post("/api/companies/" + UUID.randomUUID() + "/teams")
                        .contentType("text/plain")
                        .content("isto nao e json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_media_type"));
    }

    /**
     * 406: o caso delicado. O cliente disse que NÃO aceita JSON, e o corpo de erro é JSON — se a
     * negociação rodar de novo na escrita, a resposta sai sem corpo. A asserção de {@code $.error}
     * é justamente o que prova que o corpo saiu.
     */
    @Test
    void acceptIncompativelResponde406ComCorpo() throws Exception {
        mvc.perform(get("/actuator/health").accept("application/xml"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.error").value("not_acceptable"));
    }

    /** Controle: rota e verbo certos continuam atendidos — nada disto engoliu o roteamento. */
    @Test
    void rotaEVerboCertosContinuamRespondendo() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
