package com.orquestrador;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 0 ponta-a-ponta sobre o stack HTTP real (filtro de ator + security + interceptor
 * deny-by-default + controllers + JPA). Prova que o app sobe e que o isolamento
 * cross-tenant e enforced via HTTP, nao so na unidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Fase0HttpIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    private UUID createCompanyAs(UUID boardUser, String slug) throws Exception {
        String body = mvc.perform(post("/api/companies")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Produto " + slug + "\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = om.readTree(body);
        return UUID.fromString(node.get("id").asText());
    }

    private static String slug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Contratar EXIGE time (ADR-0045), e o time é DA COMPANY — quem contrata cria o seu. O agente
     * nasce no próprio time e vira arquiteto dele: o dispatch segue destravado (ADR-0017) e a chefia
     * derivada continua nula, o mundo que a Fase 0 já assumia.
     */
    private void contrataComTimeProprio(UUID boardUser, UUID companyId, String name, String role)
            throws Exception {
        UUID teamId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Direcao\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID agentId = idFrom(mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"teamId\":\""
                                + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
    }

    private UUID idFrom(String json) throws Exception {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    @Test
    void healthEstaUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void boardCriaEReleSuaCompany() throws Exception {
        UUID user = UUID.randomUUID();
        UUID companyId = createCompanyAs(user, slug("alpha"));
        mvc.perform(get("/api/companies/" + companyId).header("X-Board-User", user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(companyId.toString()));
    }

    @Test
    void crossTenantNaCompanyEhNegado() throws Exception {
        UUID dono = UUID.randomUUID();
        UUID intruso = UUID.randomUUID();
        UUID companyA = createCompanyAs(dono, slug("beta"));
        mvc.perform(get("/api/companies/" + companyA).header("X-Board-User", intruso.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void requisicaoSemAtorEhNegada() throws Exception {
        mvc.perform(get("/api/companies/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentesSaoCompanyScoped() throws Exception {
        UUID dono = UUID.randomUUID();
        UUID intruso = UUID.randomUUID();
        UUID companyA = createCompanyAs(dono, slug("gamma"));

        contrataComTimeProprio(dono, companyA, "CEO", "ceo");

        // intruso (sem membership) nao lista agentes de A
        mvc.perform(get("/api/companies/" + companyA + "/agents").header("X-Board-User", intruso.toString()))
                .andExpect(status().isForbidden());

        // dono lista e ve 1 agente
        mvc.perform(get("/api/companies/" + companyA + "/agents").header("X-Board-User", dono.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void slugDuplicadoRetorna409() throws Exception {
        UUID user = UUID.randomUUID();
        String s = slug("delta");
        createCompanyAs(user, s);
        mvc.perform(post("/api/companies")
                        .header("X-Board-User", user.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Outro\",\"slug\":\"" + s + "\"}"))
                .andExpect(status().isConflict());
    }
}
