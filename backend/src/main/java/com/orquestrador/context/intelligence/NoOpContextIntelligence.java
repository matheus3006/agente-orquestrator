package com.orquestrador.context.intelligence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default determinístico: não lê fontes, não agenda worker e não toca em provider. */
@Component
@ConditionalOnProperty(name = "orquestrador.context.intelligence.mode", havingValue = "off", matchIfMissing = true)
public class NoOpContextIntelligence implements ContextIntelligence {

    @Override
    public void inspect(View ignored) {
        // OFF é deliberadamente vazio.
    }
}
