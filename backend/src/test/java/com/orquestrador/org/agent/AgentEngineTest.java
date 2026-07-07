package com.orquestrador.org.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Motor por agente (ADR-0014): configureEngine pina model/effort e o adapterType decide o plano. */
class AgentEngineTest {

    private Agent agent() {
        return new Agent(UUID.randomUUID(), UUID.randomUUID(), "Lyra", "architect", null);
    }

    @Test
    void novoAgenteNasceSemMotor_modelEEffortNull_adapterProcess() {
        Agent agent = agent();
        assertThat(agent.getModel()).isNull();
        assertThat(agent.getEffort()).isNull();
        assertThat(agent.getAdapterType()).isEqualTo("process");
    }

    @Test
    void configureEngineFixaOTrio_adapterModelEffort() {
        Agent agent = agent();
        agent.configureEngine("claude-code", "opus", "ultracode");
        assertThat(agent.getAdapterType()).isEqualTo("claude-code");
        assertThat(agent.getModel()).isEqualTo("opus");
        assertThat(agent.getEffort()).isEqualTo("ultracode");
    }

    @Test
    void adapterTypeEmBrancoPreservaOAtual_modelEffortSobrescrevem() {
        Agent agent = agent();
        agent.configureEngine("codex", "gpt-5.5", "xhigh");
        // promover só model/effort (adapterType null → mantém 'codex')
        agent.configureEngine(null, "gpt-5.5", "high");
        assertThat(agent.getAdapterType()).isEqualTo("codex");
        assertThat(agent.getEffort()).isEqualTo("high");
    }

    @Test
    void brancoViraNull_defaultDoPlano() {
        Agent agent = agent();
        agent.configureEngine("claude-code", "opus", "xhigh");
        agent.configureEngine("claude-code", "  ", "");
        assertThat(agent.getModel()).isNull();
        assertThat(agent.getEffort()).isNull();
    }

    @Test
    void configureRuntimeNaoTocaOMotor() {
        Agent agent = agent();
        agent.configureEngine("claude-code", "opus", "xhigh");
        agent.configureRuntime(null, "echo hi", "LOCAL");
        assertThat(agent.getModel()).isEqualTo("opus");
        assertThat(agent.getEffort()).isEqualTo("xhigh");
        assertThat(agent.getRuntimeCommand()).isEqualTo("echo hi");
    }
}
