package com.orquestrador.handoff;

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
 * M4 ponta-a-ponta: um Run do CEO materializa {@code .claude/skills/distillation/SKILL.md} no seu
 * workspace {@code _agent} (LOCAL: o workDir É o workspace). Prova que a skill sintética atravessa o
 * mesmo seam do SkillMaterializer e sobrevive como arquivo por-run.
 */
@SpringBootTest
@ActiveProfiles("test")
class DistillationSkillMaterializedOnCeoRunTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private WorkspaceResolver workspaces;

    @Test
    void oRunDoCeoMaterializaOSkillMdDeDestilacao() throws Exception {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        ceo.configureRuntime("process", "printf '%s\\n' \"$ORQ_PROMPT\"", "LOCAL");
        UUID ceoId = agents.save(ceo).getId();

        Conversation geral = conversations.open(companyId, "Geral");
        conversations.postMessage(companyId, geral.getId(), "oi", ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        Path skillMd = workspaces.pathFor(companyId, ceoId, null)
                .resolve(".claude/skills/distillation/SKILL.md");
        assertThat(Files.exists(skillMd)).isTrue();
        assertThat(Files.readString(skillMd)).contains("# Distillation").contains("### Ponteiros");
    }
}
