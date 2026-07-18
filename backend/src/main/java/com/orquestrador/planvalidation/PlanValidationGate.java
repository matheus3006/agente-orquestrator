package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * O portão do validador no pós-run de Issue (o seam de {@code AdapterRunExecutor}). Roda o local
 * <b>fora do caminho do settle</b>, best-effort, e <b>nunca</b> deixa nada atravessar para o run —
 * Inviolável §3.6: falha do local não bloqueia.
 *
 * <p><b>O gate de escopo é "a versão pinada do agente TEM checagens?"</b> — a mesma chave da B-i.
 * Isso auto-escopa a fatia ao arquiteto sem hard-code de papel: molde sem checagens ⟹ lista vazia
 * ⟹ o local nem é chamado. Junto com ele: run no-op (o chamador não chama) e saída vazia.
 *
 * <p>Mecanismo assíncrono PRÓPRIO e simples, de propósito: a máquina do
 * {@code SpringAiContextIntelligence} (pool + timeout + fallback + cache) é para o inspetor de
 * contexto e não é template desta fatia.
 */
@Component
public class PlanValidationGate {

    private static final Logger log = LoggerFactory.getLogger(PlanValidationGate.class);

    private final Supplier<PlanValidator> validator;
    private final PlanValidationStore store;
    private final PlanValidationProperties properties;
    private final Executor workers;

    @Autowired
    public PlanValidationGate(ObjectProvider<PlanValidator> validator, PlanValidationStore store,
                              PlanValidationProperties properties) {
        // Em mode=off o bean do validador nem existe (@ConditionalOnExpression) — getIfAvailable
        // devolve null e o portão vira no-op silencioso.
        this(validator::getIfAvailable, store, properties, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "plan-validation-worker");
            thread.setDaemon(true);
            return thread;
        }));
    }

    PlanValidationGate(Supplier<PlanValidator> validator, PlanValidationStore store,
                       PlanValidationProperties properties, Executor workers) {
        this.validator = validator;
        this.store = store;
        this.properties = properties;
        this.workers = workers;
    }

    /**
     * Confere o plano contra as checagens do molde, em segundo plano. O chamador (executor) já
     * garantiu run-de-issue bem-sucedido e não-no-op; aqui ficam os guards que são deste módulo.
     */
    public void handle(PlanValidationStore.Scope scope, String plan,
                       List<RoleTemplateService.Check> checks) {
        if (properties.getMode() == PlanValidationProperties.Mode.OFF
                || checks == null || checks.isEmpty()
                || plan == null || plan.isBlank()) {
            return;
        }
        PlanValidator local = validator.get();
        if (local == null) {
            return;
        }
        try {
            workers.execute(() -> validateQuietly(local, scope, plan, checks));
        } catch (RuntimeException naoAtravessa) {
            // inclui RejectedExecutionException — que aqui só ocorre com o executor já em
            // shutdown: a fila do single-thread executor é ilimitada e não enche
            log.debug("validador de plano não agendado — o run segue: {}", naoAtravessa.toString());
        }
    }

    private void validateQuietly(PlanValidator local, PlanValidationStore.Scope scope, String plan,
                                 List<RoleTemplateService.Check> checks) {
        try {
            List<PlanCheckVerdict> verdicts = local.validate(plan, checks);
            store.record(scope, checks, verdicts, properties.getModel());
        } catch (RuntimeException falhaDoLocal) {
            log.warn("validação de plano falhou (run {} segue normal): {}", scope.runId(),
                    falhaDoLocal.toString());
        }
    }
}
