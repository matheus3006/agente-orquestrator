-- Fase 0: company + auth minima + agent + auditoria.
-- Invariante: toda entidade de dominio carrega company_id; indices liderados por company_id.

create table company (
    id         uuid        primary key,
    name       text        not null,
    slug       text        not null unique,
    created_at timestamptz not null default now()
);

create table app_user (
    id           uuid        primary key,
    email        text        not null unique,
    display_name text,
    created_at   timestamptz not null default now()
);

create table company_membership (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    user_id    uuid        not null references app_user (id),
    role       text        not null,
    created_at timestamptz not null default now(),
    unique (company_id, user_id)
);

create index idx_company_membership_company on company_membership (company_id);
create index idx_company_membership_user on company_membership (user_id);

create table agent (
    id            uuid        primary key,
    company_id    uuid        not null references company (id),
    name          text        not null,
    role          text        not null,
    reports_to_id uuid        references agent (id),
    created_at    timestamptz not null default now()
);

create index idx_agent_company on agent (company_id);
create index idx_agent_company_reports_to on agent (company_id, reports_to_id);

-- Chave de API do agente: apenas o hash SHA-256 e persistido, nunca o segredo em claro.
create table agent_api_key (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    agent_id   uuid        not null references agent (id),
    key_hash   text        not null unique,
    created_at timestamptz not null default now()
);

create index idx_agent_api_key_company on agent_api_key (company_id);
create index idx_agent_api_key_agent on agent_api_key (agent_id);

create table activity_log (
    id          uuid        primary key,
    company_id  uuid        not null references company (id),
    actor_type  text        not null,
    actor_id    uuid,
    action      text        not null,
    entity_type text,
    entity_id   uuid,
    created_at  timestamptz not null default now()
);

create index idx_activity_log_company_created on activity_log (company_id, created_at);
