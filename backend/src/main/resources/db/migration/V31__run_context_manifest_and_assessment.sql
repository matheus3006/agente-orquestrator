CREATE TABLE run_context_manifest (
    run_id UUID PRIMARY KEY REFERENCES heartbeat_run(id),
    company_id UUID NOT NULL REFERENCES company(id),
    agent_id UUID NOT NULL REFERENCES agent(id),
    issue_id UUID REFERENCES issue(id),
    audience TEXT NOT NULL,
    target TEXT NOT NULL,
    convention TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    compiled_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    final_hash VARCHAR(64) NOT NULL,
    total_bytes BIGINT NOT NULL,
    estimated_tokens BIGINT NOT NULL,
    sources JSONB NOT NULL
);

CREATE INDEX idx_context_manifest_company_compiled
    ON run_context_manifest(company_id, compiled_at DESC);

-- Projeção append-only reservada para o shadow de inteligência (F-SAI1).
-- Guarda somente metadados e findings validados; nunca prompt, resposta ou corpos das fontes.
CREATE TABLE context_assessment (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run_context_manifest(run_id),
    company_id UUID NOT NULL REFERENCES company(id),
    mode TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    status TEXT NOT NULL,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    latency_ms BIGINT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    fallback_reason TEXT,
    findings JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_context_assessment_run ON context_assessment(run_id);
CREATE INDEX idx_context_assessment_cache
    ON context_assessment(company_id, source_hash, prompt_version, provider, model, status);
