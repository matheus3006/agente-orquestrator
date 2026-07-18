package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * O portão do validador no pós-run de Issue. Ele decide QUANDO o local é chamado — e o mais
 * importante do contrato é quando ele NÃO é: sem checagens no molde (papel que não é arquiteto),
 * run no-op, saída vazia. E, acima de tudo, nada aqui pode atravessar para o run (§3.6).
 */
class PlanValidationGateTest {

    private static final List<RoleTemplateService.Check> CHECKS =
            List.of(new RoleTemplateService.Check("test", "O plano tem seção de teste?"));
    private static final PlanValidationStore.Scope SCOPE = new PlanValidationStore.Scope(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void moldeSemChecagensNemChamaOLocal() {
        AtomicInteger chamadas = new AtomicInteger();
        PlanValidationStore store = mock(PlanValidationStore.class);

        gate(chamadas, store, PlanValidationProperties.Mode.SHADOW)
                .handle(SCOPE, "um plano qualquer", List.of());

        assertThat(chamadas.get()).isZero();
        verify(store, never()).record(any(), any(), any(), any());
    }

    @Test
    void saidaVaziaDoRunNemChamaOLocal() {
        AtomicInteger chamadas = new AtomicInteger();
        PlanValidationStore store = mock(PlanValidationStore.class);

        gate(chamadas, store, PlanValidationProperties.Mode.SHADOW).handle(SCOPE, "   ", CHECKS);

        assertThat(chamadas.get()).isZero();
        verify(store, never()).record(any(), any(), any(), any());
    }

    @Test
    void modoOffNemChamaOLocal() {
        AtomicInteger chamadas = new AtomicInteger();
        PlanValidationStore store = mock(PlanValidationStore.class);

        gate(chamadas, store, PlanValidationProperties.Mode.OFF).handle(SCOPE, "um plano", CHECKS);

        assertThat(chamadas.get()).isZero();
        verify(store, never()).record(any(), any(), any(), any());
    }

    @Test
    void emSombraConfereEGravaOVeredito() {
        AtomicInteger chamadas = new AtomicInteger();
        PlanValidationStore store = mock(PlanValidationStore.class);

        gate(chamadas, store, PlanValidationProperties.Mode.SHADOW).handle(SCOPE, "um plano", CHECKS);

        assertThat(chamadas.get()).isEqualTo(1);
        verify(store).record(any(), any(), any(), any());
    }

    /**
     * O contrato de risco da fatia: Ollama fora, modelo não-puxado, store indisponível — nada
     * disso pode subir para o executor, que está no caminho do settle da Issue.
     */
    @Test
    void falhaDoValidadorNuncaAtravessaParaORun() {
        PlanValidationStore store = mock(PlanValidationStore.class);
        PlanValidator explode = (plan, checks) -> {
            throw new IllegalStateException("Ollama fora do ar");
        };
        PlanValidationGate gate = new PlanValidationGate(() -> explode, store,
                propriedades(PlanValidationProperties.Mode.SHADOW), Runnable::run);

        assertThatCode(() -> gate.handle(SCOPE, "um plano", CHECKS)).doesNotThrowAnyException();
        verify(store, never()).record(any(), any(), any(), any());
    }

    private PlanValidationGate gate(AtomicInteger chamadas, PlanValidationStore store,
                                    PlanValidationProperties.Mode mode) {
        PlanValidator validator = (plan, checks) -> {
            chamadas.incrementAndGet();
            return List.of(new PlanCheckVerdict("test", Presence.ABSENT, "sem seção de teste"));
        };
        // Executor direto: o assíncrono de produção é irrelevante para o CONTRATO testado aqui.
        return new PlanValidationGate(() -> validator, store, propriedades(mode), Runnable::run);
    }

    private PlanValidationProperties propriedades(PlanValidationProperties.Mode mode) {
        PlanValidationProperties properties = new PlanValidationProperties();
        properties.setMode(mode);
        return properties;
    }
}
