package com.orquestrador.execution;

public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RECOVERING,
    CANCELLED
}
