package com.orquestrador.execution.runtime;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolve o ExecutionTarget pelo tipo (config do Agent). */
@Component
public class ExecutionTargets {

    private final Map<ExecutionTarget.Type, ExecutionTarget> byType;

    public ExecutionTargets(List<ExecutionTarget> targets) {
        this.byType = targets.stream().collect(Collectors.toMap(ExecutionTarget::type, Function.identity()));
    }

    public ExecutionTarget forType(ExecutionTarget.Type type) {
        ExecutionTarget target = byType.get(type);
        if (target == null) {
            throw new IllegalStateException("ExecutionTarget nao registrado: " + type);
        }
        return target;
    }

    /** Tolerante: string invalida/nula cai para LOCAL (dev). */
    public static ExecutionTarget.Type parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExecutionTarget.Type.LOCAL;
        }
        try {
            return ExecutionTarget.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return ExecutionTarget.Type.LOCAL;
        }
    }
}
