package com.orquestrador.handoff;

import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.org.engine.EngineCatalog;
import com.orquestrador.org.engine.EngineCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * O gatilho do handoff (ADR-0030, D1/M1/M2/M3/D9): o turno cruzou o limiar de janela de contexto?
 *
 * <p>Módulo profundo atrás de um seam pequeno — {@link #crossed}. Esconde: o lookup engine→janela
 * ({@code engine_catalog}), a soma dos tokens de entrada REAIS emitidos no run, a divisão contra a
 * {@code context_window}, o dial de limiar e a política FAIL-OPEN. O cálculo é medido PÓS-run pelos
 * {@code CostEvent} do próprio run (D1: {@code --resume} desligado ⟹ a janela só enche por-invocação),
 * espelhando a instrumentação já anotada em {@code AdapterRunExecutor} ("handoff a 50-60%, ADR-0015").
 *
 * <p>FAIL-OPEN (D9): engine sem {@code context_window} catalogado (nullable) ⟹ NÃO dispara + WARN. O
 * cálculo é total-safe e NUNCA lança — não pode abortar o settle de um turno legítimo.
 */
@Component
public class HandoffThresholdDetector {

    private static final Logger log = LoggerFactory.getLogger(HandoffThresholdDetector.class);

    private final EngineCatalogRepository engines;
    private final int thresholdPercent;

    public HandoffThresholdDetector(
            EngineCatalogRepository engines,
            @Value("${orquestrador.handoff.context-threshold-percent:55}") int thresholdPercent) {
        this.engines = engines;
        this.thresholdPercent = thresholdPercent;
    }

    /**
     * O run consumiu ≥ limiar da janela do seu engine? {@code false} também quando a janela é
     * desconhecida (fail-open, D9) — nunca lança.
     */
    public boolean crossed(String adapterType, String model, String effort,
                           List<AgentAdapter.CostEmission> emissions) {
        Integer window = engines.findFirstByAdapterTypeAndModelAndEffort(adapterType, model, effort)
                .map(EngineCatalog::getContextWindow)
                .orElse(null);
        if (window == null || window <= 0) {
            log.warn("handoff: engine sem context_window catalogado (adapter={}, model={}, effort={}) "
                    + "— gatilho DESLIGADO neste run (fail-open, ADR-0030/D9)", adapterType, model, effort);
            return false;
        }
        long tokensInput = 0L;
        for (AgentAdapter.CostEmission emission : emissions) {
            tokensInput += emission.tokensInput();
        }
        double percent = 100.0 * tokensInput / window;
        boolean crossed = percent >= thresholdPercent;
        log.debug("handoff: run usou {}/{} tokens de contexto = {}% (limiar {}%) — cruzou={}",
                tokensInput, window, String.format("%.1f", percent), thresholdPercent, crossed);
        return crossed;
    }
}
