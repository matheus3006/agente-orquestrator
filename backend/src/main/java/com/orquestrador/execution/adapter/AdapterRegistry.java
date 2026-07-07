package com.orquestrador.execution.adapter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolve o AgentAdapter pelo tipo declarado no Agent. O ponto de extensao do "BYOA". */
@Component
public class AdapterRegistry {

    private final Map<String, AgentAdapter> byType;

    public AdapterRegistry(List<AgentAdapter> adapters) {
        this.byType = adapters.stream().collect(Collectors.toMap(AgentAdapter::type, Function.identity()));
    }

    public AgentAdapter forType(String type) {
        AgentAdapter adapter = byType.get(type == null || type.isBlank() ? "process" : type);
        if (adapter == null) {
            throw new IllegalStateException("Adapter nao registrado: " + type);
        }
        return adapter;
    }
}
