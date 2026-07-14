package com.orquestrador.skills.mcp;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 2ª PONTA do trilho MCP (F-C3, C10): o TURNO DO CEO — o consumidor primário da memória, porque é
 * ele que responde <i>"por que decidimos #7?"</i>. A F-AT2 abriu o trilho só no run de EXECUTOR
 * (D6: "personas FORA"); a F-C3 o abre também no turno, com o {@link McpToolProfile#CEO} — que serve
 * a memória e MANTÉM o corpo de skill do KnowAll fora do alcance da persona (o enforcement do perfil
 * é do core: {@code MemoryMcpBridgeTest}). Aqui se prova o WIRING; o mid-run com CLIs reais é o /verify.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpServerOnCeoTurnTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;

    @Test
    void oTurnoDoCeoTambemGanhaOServidorMcp() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();

        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "por que decidimos #7?", ActorType.BOARD,
                UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path server = workspaces.resolve(companyId, ceoId, null)
                .resolve(KnowAllMcpMaterializer.SCRIPT_RELATIVE_PATH);
        assertThat(Files.exists(server))
                .as("o turno do CEO ganha o servidor MCP (a 2ª ponta do trilho, C10)").isTrue();
    }
}
