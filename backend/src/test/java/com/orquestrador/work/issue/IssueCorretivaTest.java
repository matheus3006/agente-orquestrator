package com.orquestrador.work.issue;

import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 do caso B (ADR-0034): a task CORRETIVA nasce pelo MESMO chokepoint de criação (lock da
 * company + MAX+1, ADR-0033 intocada), mas já LIGADA à task que corrige ({@code corrige_issue_id},
 * V26) e COM DONO ({@code assignee_agent_id} pré-setado = o arquiteto do time) — nasce na mesa
 * dele: o guard do {@code tryCheckout} ({@code assignee is null or = :agentId}) garante que só o
 * arquiteto a executa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueCorretivaTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private CompanyService companies;
    @Autowired
    private MockMvc mvc;

    @Test
    void corretivaNasceLigadaComDonoEOpen() {
        UUID companyId = novaCompany();
        UUID arquitetoId = UUID.randomUUID();
        Issue original = issues.create(companyId, "Landing page", "a entregue");

        Issue corretiva = issues.createCorretiva(companyId, "Correção da #1 — Landing page",
                "a task #1 ficou ruim — o formulário não valida e-mail", original.getId(), arquitetoId);

        assertThat(corretiva.getCorrigeIssueId()).isEqualTo(original.getId());
        assertThat(corretiva.getAssigneeAgentId()).isEqualTo(arquitetoId);
        assertThat(corretiva.getStatus()).isEqualTo(IssueStatus.OPEN);
        assertThat(corretiva.getSeq()).as("#M denso pelo chokepoint existente").isEqualTo(2);
        assertThat(issues.get(companyId, original.getId()).getCorrigeIssueId())
                .as("task normal segue com a coluna vazia").isNull();
    }

    /** O pré-set do assignee entrega a corretiva na MESA do arquiteto: só ele faz o checkout. */
    @Test
    void checkoutDaCorretivaSoPeloArquiteto() {
        UUID companyId = novaCompany();
        UUID arquitetoId = UUID.randomUUID();
        UUID intruso = UUID.randomUUID();
        Issue original = issues.create(companyId, "Landing page", null);
        Issue corretiva = issues.createCorretiva(companyId, "Correção da #1 — Landing page",
                "palavras do dono", original.getId(), arquitetoId);

        assertThat(issues.checkout(companyId, corretiva.getId(), intruso, UUID.randomUUID())
                .acquired()).as("agente que não é o dono da mesa não pega").isFalse();
        assertThat(issues.checkout(companyId, corretiva.getId(), arquitetoId, UUID.randomUUID())
                .acquired()).isTrue();
    }

    /** M7: a ligação sai ADITIVA no JSON — id de máquina + o #N humano do alvo (o selo do board). */
    @Test
    void corrigeIssueIdECorrigeSeqSaemNoJsonDaLista() throws Exception {
        UUID boardUser = UUID.randomUUID();
        UUID companyId = companies.create("C", "corr-" + UUID.randomUUID().toString().substring(0, 8),
                boardUser).getId();
        Issue original = issues.create(companyId, "Landing page", null);
        issues.createCorretiva(companyId, "Correção da #1 — Landing page", "palavras do dono",
                original.getId(), UUID.randomUUID());

        mvc.perform(get("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", boardUser.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.seq == 1)].corrigeIssueId", contains(nullValue())))
                .andExpect(jsonPath("$[?(@.seq == 2)].corrigeIssueId",
                        contains(original.getId().toString())))
                .andExpect(jsonPath("$[?(@.seq == 2)].corrigeSeq", contains(1)));
    }

    private UUID novaCompany() {
        return companies.create("C", "corr-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }
}
