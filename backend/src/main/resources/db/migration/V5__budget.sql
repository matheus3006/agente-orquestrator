-- Fase 1, slice 4: budget hard-stop. cost_event = ledger (verdade); policy = limite; incident = pausa.

create table cost_event (
    id            uuid        primary key,
    company_id    uuid        not null references company (id),
    agent_id      uuid        references agent (id),
    run_id        uuid        references heartbeat_run (id),
    issue_id      uuid        references issue (id),
    provider      text,
    model         text,
    cost_cents    bigint      not null,
    tokens_input  int         not null default 0,
    tokens_output int         not null default 0,
    created_at    timestamptz not null default now()
);

create index idx_cost_company_created on cost_event (company_id, created_at);
create index idx_cost_agent on cost_event (agent_id);

create table budget_policy (
    id          uuid        primary key,
    company_id  uuid        not null references company (id),
    scope_type  text        not null,
    scope_id    uuid        not null,
    limit_cents bigint      not null,
    warn_cents  bigint,
    created_at  timestamptz not null default now()
);

create index idx_policy_scope on budget_policy (scope_type, scope_id);
create index idx_policy_company on budget_policy (company_id);

create table budget_incident (
    id          uuid        primary key,
    company_id  uuid        not null references company (id),
    scope_type  text        not null,
    scope_id    uuid        not null,
    policy_id   uuid        not null references budget_policy (id),
    spent_cents bigint      not null,
    limit_cents bigint      not null,
    status      text        not null default 'OPEN',
    created_at  timestamptz not null default now(),
    resolved_at timestamptz
);

create index idx_incident_scope_status on budget_incident (scope_type, scope_id, status);
create index idx_incident_company_created on budget_incident (company_id, created_at);
