package com.orquestrador.context.intelligence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Configuração fechada em OFF/SHADOW: ACTIVE não é um valor representável nesta fatia. */
@Component
@ConfigurationProperties("orquestrador.context.intelligence")
public class ContextIntelligenceProperties {

    public enum Mode {
        OFF,
        SHADOW
    }

    private Mode mode = Mode.OFF;
    private Duration timeout = Duration.ofSeconds(5);
    private long maxInputBytes = 65_536;
    private String promptVersion = "context-lint-v1";
    private String provider = "ollama";
    private String model = "qwen3:0.6b";
    private int workerThreads = 1;
    private int queueCapacity = 32;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
}
