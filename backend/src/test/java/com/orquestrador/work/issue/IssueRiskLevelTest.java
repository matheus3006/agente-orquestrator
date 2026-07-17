package com.orquestrador.work.issue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.identity.company.CompanyService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R.1 (ADR-0012): a Issue carrega o {@code risk_level} 1-5 que dispara o review gate (o gate em si é R.2).
 * Escopo = mapeamento/round-trip: persiste e lê de volta, default 1 quando não informado, e o wiring
 * HTTP fecha (POST com riskLevel → 201 e o valor volta no GET; fora do range 1-5 → 400).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueRiskLevelTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private CompanyService companies;
    @Autowired
    private IssueService issues;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID()).getId();
    }

    @Test
    void riskLevelPersisteELeDeVolta() {
        UUID companyId = newCompany();
        UUID issueId = issues.create(companyId, "mexe em producao", null, 5).getId();
        assertThat(issues.get(companyId, issueId).getRiskLevel()).isEqualTo(5);
    }

    @Test
    void semInformarRiskLevelAssumeUm() {
        UUID companyId = newCompany();
        UUID viaConveniencia = issues.create(companyId, "corriqueira", null).getId();
        UUID viaNull = issues.create(companyId, "sem nivel", null, null).getId();
        assertThat(issues.get(companyId, viaConveniencia).getRiskLevel()).isEqualTo(1);
        assertThat(issues.get(companyId, viaNull).getRiskLevel()).isEqualTo(1);
    }

    @Test
    void postComRiskLevelDevolve201EValorVoltaNoGet() throws Exception {
        UUID board = UUID.randomUUID();
        String slug = "co-" + UUID.randomUUID().toString().substring(0, 8);
        UUID companyId = UUID.fromString(om.readTree(mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P\",\"slug\":\"" + slug + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText());

        String issuesUrl = "/api/companies/" + companyId + "/issues";

        // F.4 (board): o detail da Issue consome a description pela MESMA resposta —
        // ela precisa fazer o round-trip completo (POST devolve, GET lista devolve).
        mvc.perform(post(issuesUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"arriscada\",\"description\":\"extrair o gateway para um seam proprio\",\"riskLevel\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskLevel").value(4))
                .andExpect(jsonPath("$.description").value("extrair o gateway para um seam proprio"));

        mvc.perform(get(issuesUrl).header("X-Board-User", board.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].riskLevel").value(4))
                .andExpect(jsonPath("$[0].description").value("extrair o gateway para um seam proprio"));

        // Sem riskLevel no corpo → default 1 no server.
        mvc.perform(post(issuesUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"corriqueira\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskLevel").value(1));

        // Fora da escala 1-5 → 422, a convenção de bean-validation do ApiExceptionHandler
        // (o risk_level É a escala do ADR-0012).
        mvc.perform(post(issuesUrl).header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"invalida\",\"riskLevel\":9}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
