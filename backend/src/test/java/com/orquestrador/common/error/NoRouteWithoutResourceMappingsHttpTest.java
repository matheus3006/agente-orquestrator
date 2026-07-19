package com.orquestrador.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A outra metade do par: sem o mapeamento de estáticos não existe o catch-all {@code /**}, então
 * o DispatcherServlet lança {@code NoHandlerFoundException} em vez de {@code NoResourceFoundException}.
 * Este app hoje roda no default (mapeamento LIGADO), mas as duas medem 500 sem o handler — o teste
 * existe para que desligar a property não reintroduza o bug pela porta dos fundos.
 */
@SpringBootTest(properties = "spring.web.resources.add-mappings=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoRouteWithoutResourceMappingsHttpTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void semMapeamentoDeEstaticosAindaResponde404() throws Exception {
        MvcResult result = mvc.perform(get("/api/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Rota inexistente: GET /api/nao-existe"))
                .andReturn();

        assertThat(result.getResolvedException())
                .isInstanceOf(org.springframework.web.servlet.NoHandlerFoundException.class);
    }
}
