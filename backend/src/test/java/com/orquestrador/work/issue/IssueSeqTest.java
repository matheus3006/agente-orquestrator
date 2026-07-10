package com.orquestrador.work.issue;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G5 (ADR-0033): identidade sequencial humana da Issue. O contrato do #N: por company,
 * denso (1, 2, 3... sem buraco), imutavel, comeca em 1. "#N e o que eu vejo, UUID e o
 * id unico para auditoria e debug."
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueSeqTest {

    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository repository;
    @Autowired
    private CompanyService companies;
    @Autowired
    private MockMvc mvc;

    @Test
    void numeraDensoAPartirDe1DentroDaCompany() {
        UUID companyId = novaCompany();

        assertThat(issues.create(companyId, "primeira", null).getSeq()).isEqualTo(1);
        assertThat(issues.create(companyId, "segunda", null).getSeq()).isEqualTo(2);
        assertThat(issues.create(companyId, "terceira", null).getSeq()).isEqualTo(3);
    }

    /**
     * A prova central da fatia: criacao de Issue NAO tem serializador natural (dono na UI +
     * approve do CEO correm em threads Tomcat concorrentes) — o lock da linha da company E o
     * serializador. Sob N criadores simultaneos na MESMA company: seqs densos 1..N, zero
     * excecao. Sem o lock (so a unique de backstop) este teste QUEBRA — criadores levariam
     * violacao de unique e a task nao nasceria.
     */
    @Test
    void nCriadoresConcorrentesNaMesmaCompanyNumeramDensoSemErro() throws Exception {
        UUID companyId = novaCompany();
        int n = 8;

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String title = "corrida-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return issues.create(companyId, title, null).getSeq();
            }));
        }
        start.countDown();

        List<Integer> seqs = new ArrayList<>();
        for (Future<Integer> f : futures) {
            seqs.add(f.get()); // excecao de qualquer criador reprova o teste — criacao nunca falha
        }
        pool.shutdown();

        assertThat(seqs).as("seqs densos 1..N, sem duplicata e sem buraco")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, n).boxed().toList());
    }

    @Test
    void companiesNumeramIndependentes() {
        UUID companyA = novaCompany();
        UUID companyB = novaCompany();

        assertThat(issues.create(companyA, "a1", null).getSeq()).isEqualTo(1);
        assertThat(issues.create(companyA, "a2", null).getSeq()).isEqualTo(2);
        assertThat(issues.create(companyB, "b1", null).getSeq())
                .as("a sequencia da company B nao ve a da A — company e a fronteira de isolamento")
                .isEqualTo(1);
    }

    // Carona do lock (M4): antes o create com company fantasma estourava na FK do Postgres
    // (e passava batido no H2, que nao tem a FK); agora e um 404 limpo em qualquer banco.
    @Test
    void companyFantasmaDa404LimpoNoCreate() {
        assertThatThrownBy(() -> issues.create(UUID.randomUUID(), "orfa", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Company nao encontrada");
    }

    // T-response: o #N e parte do contrato da API — sem ele nenhuma superficie renderiza "#12".
    @Test
    void seqSaiNoJsonDoCreateEDaLista() throws Exception {
        UUID boardUser = UUID.randomUUID();
        UUID companyId = companies.create("C", "seq-" + UUID.randomUUID().toString().substring(0, 8),
                boardUser).getId();

        mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", boardUser.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"primeira\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1));

        mvc.perform(get("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", boardUser.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(1));
    }

    /**
     * O lookup do #N que a ADR-0033 §4 já previa ("dar ao CEO busca de qualquer #N é mecanismo
     * do caso B" — ADR-0034/M1): resolve "a task #2" para a Issue; company errada não vaza
     * (o #N é identidade POR COMPANY, nunca global).
     */
    @Test
    void achaPorNumeroSemVazarEntreCompanies() {
        UUID companyA = novaCompany();
        UUID companyB = novaCompany();
        issues.create(companyA, "primeira", null);
        UUID segundaId = issues.create(companyA, "segunda", null).getId();

        assertThat(repository.findByCompanyIdAndSeq(companyA, 2))
                .map(Issue::getId).hasValue(segundaId);
        assertThat(repository.findByCompanyIdAndSeq(companyB, 2))
                .as("o #2 da company A não existe na B").isEmpty();
        assertThat(repository.findByCompanyIdAndSeq(companyA, 99)).isEmpty();
    }

    private UUID novaCompany() {
        return companies.create("C", "seq-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }
}
