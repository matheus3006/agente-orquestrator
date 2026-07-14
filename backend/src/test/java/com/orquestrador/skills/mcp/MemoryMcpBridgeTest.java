package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.Decision;
import com.orquestrador.memory.DecisionLog;
import com.orquestrador.memory.DecisionOrigem;
import com.orquestrador.memory.RacionalTipo;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.skills.SkillIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O TETO D6 DA FRENTE C (F-C3, C10): o {@code memory_fetch} AMPLIA o alcance do agente (traz racional
 * durável pro contexto sem passar por gate) — então o cinto é o mesmo do F-AT2, ESPELHADO: cap de
 * {@code #N} DISTINTOS por run + dedup (2× = 1 slot) + recusa COM MOTIVO não-fatal + evento no
 * StepTrace. O {@code memory_search} é descoberta de METADADOS — livre, fora do teto.
 *
 * <p>Guarda também o PERFIL de tools por PONTA (a decisão de kickoff desta faixa): o trilho abre nas
 * duas pontas, mas o turno do CEO recebe SÓ as tools de memória — o {@code knowall_fetch_body}
 * continua EXCLUSIVO do executor (F-AT2/D6, "personas FORA"). O enforcement é do CORE, não do script
 * materializado (que anuncia só as tools do perfil, mas despacha por nome e o agente poderia adulterar).
 */
@SpringBootTest
@ActiveProfiles("test")
class MemoryMcpBridgeTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private HeartbeatRunEventRepository runEvents;
    @Autowired
    private DecisionLog decisionLog;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private KnowAllMcpBridge bridge;

    @TempDir
    Path globalRoot;

    private UUID companyId;

    /** A ponta do EXECUTOR: run de Issue (issueId != null) — vê KnowAll + memória. */
    private McpRunScope escopoDoExecutor() {
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null)).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null, RunStatus.RUNNING)).getId();
        return new McpRunScope(companyId, agentId, UUID.randomUUID(), runId, McpToolProfile.EXECUTOR);
    }

    /** A ponta do CEO: turno de conversa — issueId NULO (C10) e SÓ as tools de memória. */
    private McpRunScope escopoDoTurnoDoCeo() {
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null)).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null, RunStatus.RUNNING)).getId();
        return new McpRunScope(companyId, agentId, null, runId, McpToolProfile.CEO);
    }

    private Decision direcao(String oQueMudou, String racional) {
        return decisionLog.record(new Decision(UUID.randomUUID(), companyId, "direcao-produto",
                DecisionLog.tipoFor("direcao-produto"), DecisionOrigem.DONO_NO_GATE, oQueMudou,
                racional, RacionalTipo.LOGICO, false));
    }

    private int eventos(UUID runId, String tipo) {
        return (int) runEvents.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(evento -> evento.getType().equals(tipo))
                .count();
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    /**
     * O teto ESPELHADO (cap=3 corpos distintos/run) + dedup + recusa com motivo: o 4º {@code #N}
     * DISTINTO é recusado — NÃO fatal (o agente lê o motivo e segue com o que tem), com evento no
     * StepTrace. O dedup (mesmo #N 2×) NÃO consome slot: já está no contexto.
     */
    @Test
    void oQuartoCorpoDistintoERecusadoComMotivo_eODedupNaoConsomeOTeto() {
        companyId = novaCompany();
        Decision d1 = direcao("cobrar por assento", "o CAC fecha");
        Decision d2 = direcao("plano anual", "trava receita");
        Decision d3 = direcao("onboarding assistido", "ativacao sobe");
        Decision d4 = direcao("suporte 24h", "churn enterprise");
        McpRunScope scope = escopoDoExecutor();
        String token = bridge.open(scope);

        assertThat(bridge.memoryFetch(token, d1.getSeq()).status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(bridge.memoryFetch(token, d2.getSeq()).status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);

        // Dedup: o #N já entregue volta SEM consumir slot (2× = 1 leitura).
        KnowAllMcpBridge.MemoryFetchOutcome rebusca = bridge.memoryFetch(token, d1.getSeq());
        assertThat(rebusca.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(rebusca.deduped()).isTrue();
        assertThat(rebusca.corpo().racional()).isEqualTo("o CAC fecha");

        // O 3º distinto ainda cabe; o 4º é RECUSADO com motivo (não-fatal).
        assertThat(bridge.memoryFetch(token, d3.getSeq()).status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        KnowAllMcpBridge.MemoryFetchOutcome recusa = bridge.memoryFetch(token, d4.getSeq());
        assertThat(recusa.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.REFUSED_CAP);
        assertThat(recusa.corpo()).as("recusa NÃO entrega corpo").isNull();
        assertThat(recusa.motivo()).contains("3").contains("#" + d4.getSeq());
        assertThat(eventos(scope.runId(), "MEMORY_FETCHED")).isEqualTo(3);
        assertThat(eventos(scope.runId(), "MEMORY_FETCH_REFUSED")).isEqualTo(1);
    }

    /**
     * A FRONTEIRA (C10.b) no bridge: o {@code #N} do log cru board-only não é entregue — e a recusa
     * NÃO consome o teto (nada entrou no contexto), então os 3 slots seguem inteiros para o durável.
     */
    @Test
    void enderecoForaDoDuravelSeladoNaoEEntregueENaoConsomeOTeto() {
        companyId = novaCompany();
        Decision operacional = decisionLog.record(new Decision(UUID.randomUUID(), companyId, "backlog",
                DecisionLog.tipoFor("backlog"), DecisionOrigem.DONO_NO_GATE, "priorizar a landing",
                "RACIONAL_SIGILOSO", RacionalTipo.LOGICO, false));
        Decision d1 = direcao("cobrar por assento", "o CAC fecha");
        Decision d2 = direcao("plano anual", "trava receita");
        Decision d3 = direcao("onboarding assistido", "ativacao sobe");
        String token = bridge.open(escopoDoExecutor());

        KnowAllMcpBridge.MemoryFetchOutcome foraDoEscopo = bridge.memoryFetch(token, operacional.getSeq());
        assertThat(foraDoEscopo.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.NOT_FOUND);
        assertThat(foraDoEscopo.corpo()).isNull();
        assertThat(foraDoEscopo.motivo()).doesNotContain("RACIONAL_SIGILOSO");

        // Endereço inexistente idem — e os 3 slots continuam inteiros.
        assertThat(bridge.memoryFetch(token, 999).status())
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.NOT_FOUND);
        assertThat(bridge.memoryFetch(token, d1.getSeq()).status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(bridge.memoryFetch(token, d2.getSeq()).status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(bridge.memoryFetch(token, d3.getSeq()).status())
                .as("os 3 slots do teto sobreviveram às recusas fora-do-escopo")
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
    }

    /** {@code memory_search} é metadado — LIVRE (nem conta, nem gasta o teto do fetch). */
    @Test
    void aBuscaDeMetadadosELivreENaoGastaOTeto() {
        companyId = novaCompany();
        Decision d1 = direcao("cobrar por assento", "o CAC fecha");
        direcao("plano anual", "trava receita");
        String token = bridge.open(escopoDoExecutor());

        for (int i = 0; i < 5; i++) {
            assertThat(bridge.memorySearch(token, "plano", 20)).isNotEmpty();
        }

        assertThat(bridge.memoryFetch(token, d1.getSeq()).status())
                .as("5 buscas não consumiram nenhum slot de corpo")
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
    }

    /**
     * O PERFIL por PONTA: no turno do CEO (issueId nulo) as tools de MEMÓRIA servem normalmente, mas
     * o {@code knowall_fetch_body} é RECUSADO pelo core — a F-AT2/D6 decidiu "personas FORA" do trilho
     * de corpo de skill, e abrir a 2ª ponta não pode ampliar autonomia por efeito colateral.
     */
    @Test
    void noTurnoDoCeoAMemoriaServeMasOCorpoDeSkillERecusado() throws IOException {
        companyId = novaCompany();
        Decision d1 = direcao("cobrar por assento", "o CAC fecha em 3 meses");
        Path dir = Files.createDirectories(globalRoot.resolve("proto-alpha"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: proto-alpha\ndescription: proto\n---\n# CORPO_DA_SKILL\n");
        indexer.syncGlobalCatalog(globalRoot);
        String token = bridge.open(escopoDoTurnoDoCeo());

        // A ponta do CEO: a memória é o consumidor primário ("por que decidimos #N?").
        assertThat(bridge.memorySearch(token, "assento", 20)).isNotEmpty();
        KnowAllMcpBridge.MemoryFetchOutcome corpo = bridge.memoryFetch(token, d1.getSeq());
        assertThat(corpo.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.OK);
        assertThat(corpo.corpo().racional()).isEqualTo("o CAC fecha em 3 meses");

        // O trilho de SKILL do executor (F-AT2/D6) continua fechado pra persona — recusa com motivo.
        KnowAllMcpBridge.FetchOutcome skill = bridge.fetchBody(token, "proto-alpha");
        assertThat(skill.status()).isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.REFUSED_SCOPE);
        assertThat(skill.text()).doesNotContain("CORPO_DA_SKILL");
        assertThat(bridge.search(token, "proto", 20)).isEmpty();
    }

    /** Sessão morta (token fechado no finally do run): nada é servido — nem metadado. */
    @Test
    void tokenDeSessaoEncerradaNaoServeMemoria() {
        companyId = novaCompany();
        Decision d1 = direcao("cobrar por assento", "o CAC fecha");
        String token = bridge.open(escopoDoExecutor());
        bridge.close(token);

        assertThat(bridge.memoryFetch(token, d1.getSeq()).status())
                .isEqualTo(KnowAllMcpBridge.FetchOutcome.Status.UNKNOWN_SESSION);
        assertThat(bridge.memorySearch(token, "assento", 20)).isEmpty();
    }
}
