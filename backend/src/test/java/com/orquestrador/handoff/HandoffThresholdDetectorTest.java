package com.orquestrador.handoff;

import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.org.engine.EngineCatalog;
import com.orquestrador.org.engine.EngineCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O gatilho de handoff medido pós-run (M1+M2+D9). Limiar de teste = 55% (application-test.yml).
 * Engines são globais e o combo (adapter,model,effort) é único por caso para não colidir entre testes.
 */
@SpringBootTest
@ActiveProfiles("test")
class HandoffThresholdDetectorTest {

    @Autowired
    private EngineCatalogRepository engines;
    @Autowired
    private HandoffThresholdDetector detector;

    private static AgentAdapter.CostEmission input(int tokensInput) {
        return new AgentAdapter.CostEmission("anthropic", "m", tokensInput, 0);
    }

    @Test
    void cruzaQuandoOsTokensDeEntradaPassamDoLimiarContraAJanela() {
        // janela 1000, entrada 600 = 60% ≥ 55% → cruza.
        engines.save(new EngineCatalog(UUID.randomUUID(), "claude-code", "det-opus-cross", "ultracode",
                1000, "det-cross", true));

        assertThat(detector.crossed("claude-code", "det-opus-cross", "ultracode", List.of(input(600)))).isTrue();
    }

    @Test
    void naoCruzaAbaixoDoLimiar() {
        // janela 1000, entrada 400 = 40% < 55% → não cruza.
        engines.save(new EngineCatalog(UUID.randomUUID(), "claude-code", "det-sonnet-below", "high",
                1000, "det-below", true));

        assertThat(detector.crossed("claude-code", "det-sonnet-below", "high", List.of(input(400)))).isFalse();
    }

    @Test
    void failOpenQuandoOEngineNaoTemJanelaCatalogada() {
        // engine que NÃO existe no catálogo (janela desconhecida) → NÃO dispara e NÃO lança (D9),
        // mesmo com um volume de tokens que estouraria qualquer janela real.
        assertThat(detector.crossed("claude-code", "det-modelo-fantasma", "high", List.of(input(999_999))))
                .isFalse();
    }
}
