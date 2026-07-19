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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rota inexistente responde 404 no formato da casa, e não o 500 que o handler genérico produzia.
 * Cobre as duas exceções que o Spring usa para o mesmo evento — ver {@link ApiExceptionHandler}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoRouteHttpTest {

    @Autowired
    private MockMvc mvc;

    /** O caso medido ao vivo: sub-recurso com typo debaixo de uma rota company-scoped real. */
    @Test
    void subRecursoInexistenteEm404EnaoEm500() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        mvc.perform(patch("/api/companies/" + companyId + "/agents/" + agentId + "/rota-que-nunca-existiu")
                        .header("X-Board-User", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value(
                        "Rota inexistente: PATCH /api/companies/" + companyId + "/agents/" + agentId + "/rota-que-nunca-existiu"));
    }

    /** Fora do prefixo company-scoped o resultado é o mesmo — o handler é global. */
    @Test
    void rotaDeTopoInexistenteEm404() throws Exception {
        mvc.perform(get("/api/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mvc.perform(get("/nao-existe-mesmo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    /** Fixa a exceção concreta desta config: se o Spring trocar de tipo, o teste conta o porquê. */
    @Test
    void aExcecaoDaConfigPadraoEhNoResourceFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/nao-existe")).andReturn();

        assertThat(result.getResolvedException())
                .isInstanceOf(org.springframework.web.servlet.resource.NoResourceFoundException.class);
    }

    /** Controle: rota REAL continua atendida — o 404 novo não engoliu o roteamento. */
    @Test
    void rotaQueExisteContinuaRespondendo() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
