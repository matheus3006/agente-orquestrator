-- F.13 (ADR-0027): preview_server_thumbnail — o PNG DURAVEL do app v2, fotografado pelo shot (5º
-- container da familia, Chromium headless) ENQUANTO o servidor de preview estava vivo (janela
-- pre-GC). content e bytea (binario nativo do Postgres; base64 em TEXT desperdicaria +33% e mentiria
-- o tipo). Por-run, append-only: sem status, sem update, sem dedup no banco — a idempotencia e a
-- guarda por-run do capturador (existsByCompanyIdAndRunId); o vigente de uma Issue e o ULTIMO.
--
-- Tabela NOVA (nao estende preview_artifact): PNG binario por-run vs HTML/TEXT por-Issue sao
-- artefatos diferentes — precedente do manifest v2 (V21), que tambem ganhou tabela propria. As rows
-- FICAM (o WorkspaceReaper coleta workspaces/volumes, NUNCA rows): esta linha e o registro visual
-- que SOBREVIVE a coleta do workspace (ADR-0026), o irmao binario da foto v1 eterna.

create table preview_server_thumbnail (
    id           uuid        primary key,
    company_id   uuid        not null references company (id),
    issue_id     uuid        not null references issue (id),
    agent_id     uuid        not null references agent (id),
    run_id       uuid        not null references heartbeat_run (id),
    content      bytea       not null,
    content_hash text        not null,
    size_bytes   int         not null,
    created_at   timestamptz not null
);

create index idx_preview_server_thumbnail_company_issue on preview_server_thumbnail (company_id, issue_id);
