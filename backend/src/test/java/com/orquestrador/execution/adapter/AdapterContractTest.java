package com.orquestrador.execution.adapter;

import com.orquestrador.execution.adapter.AgentAdapter.Context;
import com.orquestrador.execution.adapter.AgentAdapter.CostEmission;
import com.orquestrador.execution.adapter.AgentAdapter.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato do seam do motor (fatia B, ADR-0014): o {@code Context} carrega os eixos model/effort e o
 * {@code Result} carrega os CostEvents (tokens reais) — no sucesso E na falha. Forma pura, sem Spring.
 */
class AdapterContractTest {

    @Test
    void contextCarregaModelEEffort() {
        Context ctx = new Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                null, "cmd", null, null, "vol", null, "opus", "high");
        assertThat(ctx.model()).isEqualTo("opus");
        assertThat(ctx.effort()).isEqualTo("high");
    }

    @Test
    void okSemCostEventsDefaultaVazio() {
        Result result = Result.ok("sess");
        assertThat(result.succeeded()).isTrue();
        assertThat(result.session()).isEqualTo("sess");
        assertThat(result.costEvents()).isEmpty();
    }

    @Test
    void okComCostEventsCarregaOsTokens() {
        CostEmission emission = new CostEmission("anthropic", "opus", 120, 45);
        Result result = Result.ok("sess", List.of(emission));
        assertThat(result.succeeded()).isTrue();
        assertThat(result.costEvents()).containsExactly(emission);
    }

    @Test
    void failSemCostEventsDefaultaVazio() {
        Result result = Result.fail("estourou");
        assertThat(result.succeeded()).isFalse();
        assertThat(result.error()).isEqualTo("estourou");
        assertThat(result.costEvents()).isEmpty();
    }

    @Test
    void failComCostEventsContaTokensGastosNaFalha() {
        // Tokens gastos ANTES da falha contam (o executor roteia costEvents no sucesso E na falha).
        CostEmission emission = new CostEmission("anthropic", "opus", 90, 0);
        Result result = Result.fail("recusou o modelo", List.of(emission));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.costEvents()).containsExactly(emission);
    }

    @Test
    void costEventsSaoImutaveisMesmoComListaMutavelDeEntrada() {
        List<CostEmission> mutavel = new ArrayList<>();
        mutavel.add(new CostEmission("anthropic", "opus", 10, 5));
        Result result = Result.ok("sess", mutavel);
        // cópia defensiva: mutar a lista de entrada não afeta o Result, e a lista do Result é imutável.
        mutavel.clear();
        assertThat(result.costEvents()).hasSize(1);
        assertThatThrownBy(() -> result.costEvents().add(new CostEmission("x", "y", 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
