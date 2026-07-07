-- F.7 (ADR-0014): catálogo CURADO de motores (adapter + model + effort). Global (sem company_id,
-- como o catálogo de Skills — Camada 1). SUGERE combinações para a UI (com label legível e a janela
-- de contexto que alimenta o handoff a 50-60%, ADR-0015); NÃO restringe o dado — quem valida o modelo
-- é o adapter contra o CLI (ADR-0014/Q2). Modelo novo = INSERT (sem migration nem redeploy).
create table engine_catalog (
    id             uuid        primary key,
    adapter_type   text        not null,
    model          text,
    effort         text,
    context_window int,
    label          text        not null,
    enabled        boolean     not null default true,
    created_at     timestamptz not null default now()
);

create index idx_engine_catalog_enabled on engine_catalog (enabled);

-- Seed curado (editável). effort='ultracode' é traduzido pelo ClaudeCodeAdapter para
-- --settings '{"ultracode":true}' (session-only, NÃO é --effort); os demais viram
-- --effort <effort> (claude) / -c model_reasoning_effort=<effort> (codex). model/effort null = process.
insert into engine_catalog (id, adapter_type, model, effort, context_window, label, enabled) values
  (gen_random_uuid(), 'claude-code', 'opus',    'ultracode', 200000, 'Claude · Opus 4.8 · ultra',   true),
  (gen_random_uuid(), 'claude-code', 'opus',    'xhigh',     200000, 'Claude · Opus 4.8 · xhigh',   true),
  (gen_random_uuid(), 'claude-code', 'sonnet',  'high',      200000, 'Claude · Sonnet 5 · high',    true),
  (gen_random_uuid(), 'claude-code', 'haiku',   'medium',    200000, 'Claude · Haiku 4.5 · rápido', true),
  (gen_random_uuid(), 'codex',       'gpt-5.5', 'xhigh',     256000, 'GPT · 5.5 · xhigh',           true),
  (gen_random_uuid(), 'codex',       'gpt-5.5', 'high',      256000, 'GPT · 5.5 · high',            true),
  (gen_random_uuid(), 'process',     null,      null,        null,   'Process · demo/echo',         true);
