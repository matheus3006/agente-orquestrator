package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatRun;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O callback loopback do servidor MCP do KnowAll (F-AT2): token válido do run → 200 com corpo/hits;
 * token ausente ou desconhecido → 401 (sessão morta). É o mesmo caminho que o script Node materializado
 * exercita no /verify — aqui provado por MockMvc contra o bridge real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowAllMcpBridgeHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private HeartbeatRunRepository runs;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private KnowAllMcpBridge bridge;
    @Autowired
    private DecisionLog decisionLog;

    @TempDir
    Path globalRoot;

    private String seedAndOpen() throws IOException {
        String nonce = "http" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = Files.createDirectories(globalRoot.resolve("probe-" + nonce));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: probe-" + nonce + "\ndescription: sonda " + nonce + "\n---\n# corpo BODY_" + nonce + "\n");
        indexer.syncGlobalCatalog(globalRoot);
        this.companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        UUID agentId = agents.save(new Agent(UUID.randomUUID(), companyId, "Vega", "executor", null)).getId();
        UUID runId = runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, null, RunStatus.RUNNING)).getId();
        this.probeKey = "probe-" + nonce;
        this.nonce = nonce;
        return bridge.open(new McpRunScope(companyId, agentId, null, runId, McpToolProfile.EXECUTOR));
    }

    private String probeKey;
    private String nonce;
    private UUID companyId;

    @Test
    void tokenValidoBuscaEBuscaCorpo() throws Exception {
        String token = seedAndOpen();

        mvc.perform(post("/internal/mcp/knowall/fetch-body")
                        .header("X-Orq-Mcp-Token", token)
                        .contentType("application/json")
                        .content("{\"skillKey\":\"" + probeKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("BODY_" + nonce)));

        mvc.perform(post("/internal/mcp/knowall/search")
                        .header("X-Orq-Mcp-Token", token)
                        .contentType("application/json")
                        .content("{\"query\":\"" + nonce + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillKey").value(probeKey));
    }

    @Test
    void tokenAusenteOuDesconhecidoRecusa401() throws Exception {
        seedAndOpen();

        mvc.perform(post("/internal/mcp/knowall/fetch-body")
                        .contentType("application/json")
                        .content("{\"skillKey\":\"" + probeKey + "\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/internal/mcp/knowall/fetch-body")
                        .header("X-Orq-Mcp-Token", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"skillKey\":\"" + probeKey + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A 2ª família de rotas do MESMO canal (F-C3, C10): {@code /internal/mcp/memory/*} — o contrato
     * de URL que o script materializado chama. Mesmo token, mesmo guard 401. A recusa de negócio
     * (fora do durável selado) volta 200 com {@code motivo} — não-fatal, como manda o D4.
     */
    @Test
    void oCanalDaMemoriaRespondeNoMesmoTokenERecusa401SemEle() throws Exception {
        String token = seedAndOpen();
        Decision direcao = decisionLog.record(new Decision(UUID.randomUUID(), companyId,
                "direcao-produto", DecisionLog.tipoFor("direcao-produto"), DecisionOrigem.DONO_NO_GATE,
                "cobrar por assento " + nonce, "o CAC fecha " + nonce, RacionalTipo.LOGICO, false));

        mvc.perform(post("/internal/mcp/memory/search")
                        .header("X-Orq-Mcp-Token", token)
                        .contentType("application/json")
                        .content("{\"query\":\"" + nonce + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(direcao.getSeq()));

        mvc.perform(post("/internal/mcp/memory/fetch")
                        .header("X-Orq-Mcp-Token", token)
                        .contentType("application/json")
                        .content("{\"seq\":" + direcao.getSeq() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.corpo.racional")
                        .value(org.hamcrest.Matchers.containsString("o CAC fecha")));

        mvc.perform(post("/internal/mcp/memory/fetch")
                        .contentType("application/json")
                        .content("{\"seq\":" + direcao.getSeq() + "}"))
                .andExpect(status().isUnauthorized());
    }
}
