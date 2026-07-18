package com.orquestrador.planvalidation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Config do validador de planos (Uso B / B-ii). Os defaults NÃO são palpite — saíram do harness
 * empírico rodado contra o Ollama antes de existir código de produção (ver
 * {@code docs/medicao-validador-qwen.md}):
 *
 * <ul>
 *   <li><b>model {@code qwen3:1.7b}</b> — o {@code qwen3:0.6b} do inspetor de contexto foi medido
 *       como preditor CONSTANTE (respondeu "presente" em 18/18, = a taxa-base do conjunto, em 11
 *       formatos de prompt diferentes): inutilizável como validador.</li>
 *   <li><b>think {@code true}</b> — direção medida na redação CONCRETA: 12/18→14/18; a config
 *       completa (1.7b + think + redação seed) mediu 26/27; seed+think=false não medido.</li>
 *   <li><b>numPredict 1500</b> — com 150 o raciocínio come o orçamento e 14 de 18 respostas vêm
 *       VAZIAS; 300 ainda trunca em plano longo; 800 já basta; 1500 é folga de graça (o modelo
 *       para sozinho, a latência não cresce).</li>
 * </ul>
 *
 * <p>O {@link Mode} é o <b>kill-switch GLOBAL</b> e {@link #pushDisabledRoles} o <b>por-papel</b>:
 * baixar de {@code PUSH} para {@code SHADOW} desliga o empurrão em produção na hora, sem parar a
 * telemetria que mede a precisão.
 */
@Component
@ConfigurationProperties("orquestrador.plan-validation")
public class PlanValidationProperties {

    public enum Mode {
        /** Desligado: o seam nem chama o local. */
        OFF,
        /** Valida e REGISTRA (telemetria), mas não empurra nada para o arquiteto. */
        SHADOW,
        /** Valida, registra E empurra o checklist para o run seguinte da mesma issue. */
        PUSH
    }

    private Mode mode = Mode.OFF;
    private String model = "qwen3:1.7b";
    private boolean think = true;
    private int numPredict = 1500;
    private Duration timeout = Duration.ofSeconds(120);
    private int maxInputChars = 20_000;
    /** Kill-switch por-papel: o papel listado aqui NUNCA recebe o push, mesmo em {@code PUSH}. */
    private List<String> pushDisabledRoles = new ArrayList<>();

    /** O push só acontece em {@code PUSH} e para papel fora da lista de bloqueio. */
    public boolean pushEnabledFor(String role) {
        if (mode != Mode.PUSH) {
            return false;
        }
        return pushDisabledRoles.stream().noneMatch(blocked -> blocked.equalsIgnoreCase(role));
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isThink() { return think; }
    public void setThink(boolean think) { this.think = think; }
    public int getNumPredict() { return numPredict; }
    public void setNumPredict(int numPredict) { this.numPredict = numPredict; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
    public List<String> getPushDisabledRoles() { return pushDisabledRoles; }
    public void setPushDisabledRoles(List<String> pushDisabledRoles) {
        this.pushDisabledRoles = pushDisabledRoles == null ? new ArrayList<>() : pushDisabledRoles;
    }
}
