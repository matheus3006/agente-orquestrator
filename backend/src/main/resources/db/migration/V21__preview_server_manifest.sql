-- F.10 (ADR-0023): preview_server_manifest — a DECLARACAO de servidor de um Run, lida no settle
-- pelo contrato de lugar /workspace/.orq/preview/server.json (leitor hermetico, cap 4KB, parse
-- estrito) e persistida por run. Append-only: sem dedup, sem status, sem update — o vigente de
-- uma Issue e o ULTIMO (runs serializados pelo execution_lock). O banco guarda SO a declaracao;
-- o runtime (vivo/porta do host) mora no Docker por label — tabela de vivos = estado especular,
-- rejeitada (ADR-0016 residual c).

create table preview_server_manifest (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    issue_id   uuid        not null references issue (id),
    agent_id   uuid        not null references agent (id),
    run_id     uuid        not null references heartbeat_run (id),
    command    text        not null,
    port       int         not null,
    created_at timestamptz not null
);

create index idx_preview_server_manifest_company_issue on preview_server_manifest (company_id, issue_id);
