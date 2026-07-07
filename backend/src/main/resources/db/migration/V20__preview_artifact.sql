-- F.9 (ADR-0021): preview_artifact — a foto imutavel do prototipo publicado por um Run.
-- Capturada no settle de run SUCCEEDED pelo contrato de lugar /workspace/.orq/preview/index.html
-- (leitor hermetico: --network none, volume :ro, imagem do proprio adapter). Append-only:
-- sem status, sem update, sem soft-delete. version e ordinal por Issue ("a review R aprovou
-- a v3") — seguro porque os runs de uma Issue sao serializados pelo execution_lock. Dedup por
-- content_hash contra a ULTIMA versao da mesma Issue acontece no capturador, nao no banco.

create table preview_artifact (
    id           uuid        primary key,
    company_id   uuid        not null references company (id),
    issue_id     uuid        not null references issue (id),
    agent_id     uuid        not null references agent (id),
    run_id       uuid        not null references heartbeat_run (id),
    version      int         not null,
    content      text        not null,
    content_hash text        not null,
    size_bytes   int         not null,
    created_at   timestamptz not null,
    constraint uq_preview_artifact_issue_version unique (issue_id, version)
);

create index idx_preview_artifact_company_issue on preview_artifact (company_id, issue_id);
