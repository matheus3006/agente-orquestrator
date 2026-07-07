-- Fase 1, slice 2: motor de heartbeat (DB-as-queue).
-- Invariantes: 1 Run ativo por Agent; lease para recovery; coalescing de wakeups.

create table heartbeat_run (
    id               uuid        primary key,
    company_id       uuid        not null references company (id),
    agent_id         uuid        not null references agent (id),
    issue_id         uuid        references issue (id),
    status           text        not null default 'QUEUED',
    attempt          int         not null default 0,
    lease_owner      text,
    lease_expires_at timestamptz,
    needs_followup   boolean     not null default false,
    last_error       text,
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz
);

create index idx_run_company_status on heartbeat_run (company_id, status);
create index idx_run_status_created on heartbeat_run (status, created_at);
create index idx_run_status_lease on heartbeat_run (status, lease_expires_at);

-- Guard de corrida de PRODUCAO (Postgres): no maximo 1 Run ativo por Agent.
-- (Indice parcial e Postgres-only; nos testes H2 a serializacao e feita por lock na linha do Agent.)
create unique index uq_run_active_agent on heartbeat_run (agent_id)
    where status in ('QUEUED', 'RUNNING', 'RECOVERING');

create table agent_wakeup_request (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    agent_id   uuid        not null references agent (id),
    issue_id   uuid        references issue (id),
    reason     text        not null,
    status     text        not null default 'PENDING',
    run_id     uuid        references heartbeat_run (id),
    created_at timestamptz not null default now()
);

create index idx_wakeup_company_agent on agent_wakeup_request (company_id, agent_id);
create index idx_wakeup_run on agent_wakeup_request (run_id);

create table heartbeat_run_event (
    id         uuid        primary key,
    run_id     uuid        not null references heartbeat_run (id),
    company_id uuid        not null references company (id),
    type       text        not null,
    detail     text,
    created_at timestamptz not null default now()
);

create index idx_run_event_run on heartbeat_run_event (run_id, created_at);
