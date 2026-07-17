package com.orquestrador.context.intelligence;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "orquestrador.context.intelligence.mode=off")
@ActiveProfiles("test")
class ContextIntelligenceOffIntegrationTest {

    @Autowired
    private ContextIntelligence intelligence;

    @Autowired
    private ApplicationContext application;

    @Test
    void offSelecionaNoOpSemMemoriaToolsOuChatClientGenerico() {
        assertThat(intelligence).isInstanceOf(NoOpContextIntelligence.class);
        assertThat(application.getBeansOfType(ChatMemory.class)).isEmpty();
        assertThat(application.getBeansOfType(ToolCallback.class)).isEmpty();
        assertThat(application.getBeansOfType(ChatClient.Builder.class)).isEmpty();
    }
}
