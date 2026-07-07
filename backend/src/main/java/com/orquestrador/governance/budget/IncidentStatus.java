package com.orquestrador.governance.budget;

public enum IncidentStatus {
    /** Hard limit cruzado: o escopo esta PAUSADO (preflight bloqueia). */
    OPEN,
    /** Override aprovado: o escopo volta a operar. */
    RESOLVED
}
