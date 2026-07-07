package com.orquestrador.memory;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A carta materializada (ADR-0018/0019): a versão CORRENTE de company_memory vira
 * {@code .claude/CLAUDE.md} no workspace do Run de turno; sem carta, o arquivo some
 * (idempotente — nunca serve carta velha).
 */
@SpringBootTest
@ActiveProfiles("test")
class CompanyMemoryMaterializationTest {

    @Autowired
    private CompanyMemoryMaterializer materializer;
    @Autowired
    private CompanyMemoryRepository memories;
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

    @TempDir
    Path workDir;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    @Test
    void materializaAVersaoCorrenteERemoveQuandoNaoHaCarta() throws Exception {
        UUID companyId = novaCompany();
        Path claudeMd = workDir.resolve(".claude/CLAUDE.md");

        // Sem carta: nada é escrito.
        materializer.materialize(workDir, companyId);
        assertThat(Files.exists(claudeMd)).isFalse();

        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta v1", 1));
        materializer.materialize(workDir, companyId);
        assertThat(Files.readString(claudeMd)).isEqualTo("# Carta v1");

        // Versão mais nova VENCE (append-only: v2 não apaga v1 no banco, mas o arquivo é o corrente).
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA, "# Carta v2", 2));
        materializer.materialize(workDir, companyId);
        assertThat(Files.readString(claudeMd)).isEqualTo("# Carta v2");

        // Carta de OUTRA company nunca vaza; sem carta própria o arquivo é REMOVIDO.
        UUID outraCompany = novaCompany();
        materializer.materialize(workDir, outraCompany);
        assertThat(Files.exists(claudeMd)).isFalse();
    }

    @Test
    void runDeTurnoMaterializaACartaNoWorkspaceDoCeo() throws Exception {
        UUID companyId = novaCompany();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s' \"$ORQ_PROMPT\"", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, MemoryKind.CARTA,
                "# Interesses da empresa\n- foco no MVP", 1));

        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path claudeMd = workspaces.resolve(companyId, ceoId, null).resolve(".claude/CLAUDE.md");
        assertThat(Files.readString(claudeMd)).contains("foco no MVP");
    }
}
