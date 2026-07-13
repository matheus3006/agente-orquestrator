package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.skills.KnowAllService;
import com.orquestrador.skills.SkillIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O TETO do #1 (D4) do F-AT2, enforcement de PRIMEIRA CLASSE no core: cap-por-run de corpos DISTINTOS,
 * dedup por skill (2× = 1 leitura, não consome o teto) e recusa COM MOTIVO não-fatal + eventos leves
 * ({@code SKILL_BODY_FETCHED}/{@code SKILL_BODY_FETCH_REFUSED}, D5). Aqui não há clique do dono — o
 * número é o cinto, provado por teste (a prova viva com CLIs reais é o /verify).
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowAllMcpBridgeTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private KnowAllMcpBridge bridge;

    @TempDir
    Path globalRoot;

    private String nonce;

    private McpRunScope novoRunScope() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null)).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null, RunStatus.RUNNING)).getId();
        return new McpRunScope(companyId, agentId, null, runId);
    }

    /** Semeia N skills no catálogo GLOBAL com corpos marcados; devolve o prefixo das chaves. */
    private String seedGlobal(int count) throws IOException {
        nonce = "br" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= count; i++) {
            Path dir = Files.createDirectories(globalRoot.resolve("sk" + i + "-" + nonce));
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: sk" + i + "-" + nonce + "\ndescription: busca " + nonce + "\n---\n# corpo BODY_sk" + i + "_" + nonce + "\n");
        }
        indexer.syncGlobalCatalog(globalRoot);
        return "sk";
    }

    @Test
    void fetchBodyDevolveOCorpoERegistraOEvento() throws IOException {
        seedGlobal(1);
        McpRunScope scope = novoRunScope();
        String token = bridge.open(scope);

        KnowAllMcpBridge.FetchOutcome outcome = bridge.fetchBody(token, "sk1-" + nonce);

        assertThat(outcome.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(outcome.deduped()).isFalse();
        assertThat(outcome.text()).contains("BODY_sk1_" + nonce);
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(scope.runId()))
                .anySatisfy(event -> assertThat(event.getType()).isEqualTo("SKILL_BODY_FETCHED"));
    }

    @Test
    void searchDevolveMetadadosDaCompanyDoRun() throws IOException {
        seedGlobal(2);
        String token = bridge.open(novoRunScope());

        List<KnowAllService.SearchResult> hits = bridge.search(token, nonce, 20);

        assertThat(hits).extracting(KnowAllService.SearchResult::skillKey)
                .containsExactly("sk1-" + nonce, "sk2-" + nonce);
    }

    @Test
    void mesmoCorpoBuscadoDuasVezesNaoConsomeOTeto() throws IOException {
        // Dedup ANTES do cap: o CAP+1 fetches são todos da MESMA skill ⟹ 1 leitura distinta, nunca recusa.
        seedGlobal(1);
        McpRunScope scope = novoRunScope();
        String token = bridge.open(scope);

        KnowAllMcpBridge.FetchOutcome first = bridge.fetchBody(token, "sk1-" + nonce);
        assertThat(first.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(first.deduped()).isFalse();

        for (int i = 0; i < KnowAllMcpBridge.CAP_BODIES_POR_RUN + 3; i++) {
            KnowAllMcpBridge.FetchOutcome again = bridge.fetchBody(token, "sk1-" + nonce);
            assertThat(again.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
            assertThat(again.deduped()).isTrue();
            assertThat(again.text()).contains("BODY_sk1_" + nonce);
        }
        // Um SÓ evento de entrega (as re-entregas não emitem novo SKILL_BODY_FETCHED).
        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(scope.runId()))
                .filteredOn(event -> event.getType().equals("SKILL_BODY_FETCHED")).hasSize(1);
    }

    @Test
    void oCorpoAlemDoTetoERecusadoComMotivoNaoFatalEEventoVisivel() throws IOException {
        int cap = KnowAllMcpBridge.CAP_BODIES_POR_RUN;
        seedGlobal(cap + 1);
        McpRunScope scope = novoRunScope();
        String token = bridge.open(scope);

        for (int i = 1; i <= cap; i++) {
            assertThat(bridge.fetchBody(token, "sk" + i + "-" + nonce).status())
                    .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        }
        // O (cap+1)-ésimo DISTINTO: recusado com motivo, NÃO fatal (retorna outcome, não lança).
        KnowAllMcpBridge.FetchOutcome refused = bridge.fetchBody(token, "sk" + (cap + 1) + "-" + nonce);
        assertThat(refused.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.REFUSED_CAP);
        assertThat(refused.text()).contains("teto").contains("siga com o que já tem");

        assertThat(runEvents.findByRunIdOrderByCreatedAtAsc(scope.runId()))
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo("SKILL_BODY_FETCH_REFUSED");
                    assertThat(event.getDetail()).contains("sk" + (cap + 1) + "-" + nonce);
                });
    }

    @Test
    void chaveInexistenteDevolveNotFoundSemConsumirOTeto() throws IOException {
        seedGlobal(KnowAllMcpBridge.CAP_BODIES_POR_RUN);
        McpRunScope scope = novoRunScope();
        String token = bridge.open(scope);

        // Uma busca por chave inexistente NÃO conta contra o teto...
        KnowAllMcpBridge.FetchOutcome missing = bridge.fetchBody(token, "nao-existe-" + nonce);
        assertThat(missing.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.NOT_FOUND);

        // ...então ainda cabem CAP corpos distintos de verdade.
        for (int i = 1; i <= KnowAllMcpBridge.CAP_BODIES_POR_RUN; i++) {
            assertThat(bridge.fetchBody(token, "sk" + i + "-" + nonce).status())
                    .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        }
    }

    @Test
    void tokenDesconhecidoOuEncerradoRecusa() throws IOException {
        seedGlobal(1);
        McpRunScope scope = novoRunScope();
        String token = bridge.open(scope);
        bridge.close(token);

        assertThat(bridge.fetchBody(token, "sk1-" + nonce).status())
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.UNKNOWN_SESSION);
        assertThat(bridge.search(token, nonce, 20)).isEmpty();
        assertThat(bridge.fetchBody("token-que-nunca-existiu", "sk1-" + nonce).status())
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.UNKNOWN_SESSION);
    }
}
